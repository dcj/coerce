(ns coerce.jdbc.pg
  (:require
   [clojure.string :as string]
   [java-time :as time]
   [next.jdbc :as jdbc]
   [next.jdbc.connection :as connection]
   [next.jdbc.result-set :as result-set]
   [next.jdbc.prepare    :as prepare]
   [geo.io]
   [cheshire.core :as json]
   )
  (:import [org.postgresql.util PGobject]
           ;; [org.postgis]
           ;; [org.postgis Geometry
           ;;  ComposedGeom GeometryCollection MultiLineString
           ;;  MultiPolygon PointComposedGeom LinearRing LineString
           ;;  MultiPoint Polygon Point]
           [java.time Instant]
           [java.sql PreparedStatement ParameterMetaData Timestamp]
           [org.threeten.extra Interval]
           )
  )

;;
;; multimethod selector for conversion funcs
;;

(defn ^:private parameter-dispatch-fn
  [_ type-name]
  (keyword type-name))

;;
;; Convert to Postgres JSON
;;

(defn ->pg-json
  [data json-type]
  (doto (PGobject.)
    (.setType (name json-type))
    (.setValue (json/generate-string data))))

;;
;; Convert Clojure maps to SQL parameter values
;;

(defmulti map->parameter parameter-dispatch-fn)

;; (defmethod map->parameter :geometry
;;   [m _]
;;   (jdbc/sql-value (coerce/geojson->postgis m)))

(defmethod map->parameter :json
  [m _]
  (->pg-json m :json))

(defmethod map->parameter :jsonb
  [m _]
  (->pg-json m :jsonb))

;;
;; Convert clojure vectors to SQL parameter values
;;

(defmulti vec->parameter parameter-dispatch-fn)

(defmethod vec->parameter :json
  [v _]
  (->pg-json v :json))

(defmethod vec->parameter :jsonb
  [v _]
  (->pg-json v :jsonb))

(defmethod vec->parameter :inet
  [v _]
  (if (= (count v) 4)
    (doto (PGobject.)
      (.setType "inet")
      (.setValue (string/join "." v)))
    v))

(defmethod vec->parameter :default
  [v _]
  v)

(extend-protocol prepare/SettableParameter

  java.time.ZonedDateTime

  (set-parameter [^java.time.Instant v ^PreparedStatement ps ^long i]
    (.setTimestamp ps i (java.sql.Timestamp/from (time/instant v))))

  org.threeten.extra.Interval

  (set-parameter [^org.threeten.extra.Interval v ^PreparedStatement ps ^long i]
    (let [meta      (.getParameterMetaData ps)
          type-name (.getParameterTypeName meta i)
          start     (time/start v)
          end       (time/end v)
          start-pg  (if (= start Instant/MIN) "" start)
          end-pg    (if (= end Instant/MAX) "" end)
          value-pg  (str "[" start-pg "," end-pg ")")]
      (.setObject ps i (doto (PGobject.)
                         (.setType type-name)
                         (.setValue value-pg)))))

  clojure.lang.IPersistentMap

  (set-parameter [m ^PreparedStatement s ^long i]
    (let [meta (.getParameterMetaData s)]
      (if-let [type-name (keyword (.getParameterTypeName meta i))]
        (.setObject s i (map->parameter m type-name))
        (.setObject s i m))))

  clojure.lang.IPersistentVector

  (set-parameter [v ^PreparedStatement s ^long i]
    (let [conn      (.getConnection s)
          meta      (.getParameterMetaData s)
          type-name (.getParameterTypeName meta i)]
      (if-let [elem-type (when type-name (second (re-find #"^_(.*)" type-name)))]
        (.setObject s i (.createArrayOf conn elem-type (to-array v)))
        (.setObject s i (vec->parameter v type-name)))))

  clojure.lang.Keyword

  (set-parameter [^clojure.lang.Keyword v ^PreparedStatement s ^long i]
    (let [type-name (-> v namespace (string/replace "-" "_"))
          value-pg  (name v)]
      (.setObject s i (doto (PGobject.)
                        (.setType type-name)
                        (.setValue value-pg)))))

  )

;;
;; PGobject parsing helper fns
;;

(defn ^:private pg-vector->clj
  "oidvector, int2vector, etc. are space separated lists"
  [s]
  (when (seq s)
    (string/split s #"\s+")))

(defn ^:private pg-array->clj
  "Arrays are of form {1,2,3}"
  [s]
  (when (seq s)
    (when-let [[_ content] (re-matches #"^\{(.+)\}$" s)]
      (if-not (empty? content) (string/split content #"\s*,\s*") []))))

(defn ^:private parse-range
  [s]
  (let [len         (count s)
        len-1       (dec len)
        start-delim (subs s 0 1)
        end-delim   (subs s len-1 len)
        ranges      (subs s 1 len-1)
        [start end] (-> ranges
                        (string/replace #"\"" "")
                        (string/split #","))]
    [start-delim start end end-delim]))

(defn ^:private pgobject->interval
  [type s]
  (let [[_ start-str end-str _] (parse-range s)
        start                   (string/replace start-str #" " "T")
        end                     (string/replace end-str #" " "T")
        time-fn                 (case type
                                  :tstzrange time/zoned-date-time
                                  :tsrange   (comp time/instant #(str % "Z")))]
    (time/interval (time-fn start)
                   (time-fn end))))

;;
;; PGobject parsing magic
;;

(defmulti pgobject->clj
  "Convert returned PGobject to Clojure value."
  #(keyword (when % (.getType ^org.postgresql.util.PGobject %))))

(defmethod pgobject->clj :oidvector
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (mapv read-string (pg-vector->clj val))))

(defmethod pgobject->clj :int2vector
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (mapv read-string (pg-vector->clj val))))

(defmethod pgobject->clj :anyarray
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (vec (pg-array->clj val))))

(defmethod pgobject->clj :json
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

(defmethod pgobject->clj :jsonb
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (json/parse-string val true)))

;; PostgreSQL comes with the following built-in range types:
;;   int4range — Range of integer
;;   int8range — Range of bigint
;;   numrange — Range of numeric
;;   tsrange — Range of timestamp without time zone
;;   tstzrange — Range of timestamp with time zone
;;   daterange — Range of date

(defmethod pgobject->clj :tstzrange
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (pgobject->interval :tstzrange val)))

(defmethod pgobject->clj :tsrange
  [^org.postgresql.util.PGobject x]
  (when-let [val (.getValue x)]
    (pgobject->interval :tsrange val)))

;; Removed for debugging, DCJ 2020-10-17
;; (defmethod pgobject->clj :geometry
;;   [^org.postgresql.util.PGobject x]
;;   (when-let [val (.getValue x)]
;;     (geo.io/read-wkb-hex val)))

(defmethod pgobject->clj :default
  [^org.postgresql.util.PGobject x]
  (println "Unhandled pgobject:" (.getType x)) ;; May want to comment this out, but hopefully good for new pgobject support
  (.getValue x))

;; https://www.bevuta.com/en/blog/using-postgresql-enums-in-clojure/

(def +schema-enums+
  "A set of all PostgreSQL enums in schema.sql. Used to convert
  enum-values back into Clojure keywords.
  TODO: dig this out of: select typname from pg_type where typtype='e';"
  #{"weighting"})

(extend-protocol result-set/ReadableColumn

  java.lang.String

  (read-column-by-label ^java.lang.String [v _] ;; TODO won't work for plan
    v)
  (read-column-by-index ^java.lang.String [v rsmeta index]
    (let [type (.getColumnTypeName rsmeta index)]
      (if (contains? +schema-enums+ type)
        (keyword (string/replace type "_" "-") v)
        v)))

  java.sql.Timestamp

  (read-column-by-label ^java.time.ZonedDateTime [^java.sql.Timestamp v _]
    (time/zoned-date-time (time/instant v) "UTC"))
  (read-column-by-index ^java.time.ZonedDateTime [^java.sql.Timestamp v _2 _3]
    (time/zoned-date-time (time/instant v) "UTC"))

  ;; PGobjects have their own multimethod
  org.postgresql.util.PGobject

  (read-column-by-label ^org.postgresql.util.PGobject [^org.postgresql.util.PGobject v _]
    (pgobject->clj v))
  (read-column-by-index ^org.postgresql.util.PGobject [^org.postgresql.util.PGobject v _2 _3]
    (pgobject->clj v))

  ;; Convert java.sql.Array to Clojure vector
  java.sql.Array

  (read-column-by-label ^java.sql.Array [^java.sql.Array v _]
    (vec (.getArray val)))
  (read-column-by-index ^java.sql.Array [^java.sql.Array v _2 _3]
    (vec (.getArray val)))

  ;; ;; Return the PostGIS geometry object instead of PGgeometry wrapper
  ;; org.postgis.PGgeometry
  ;; (result-set-read-column [val _ _]
  ;;   (coerce/postgis->geojson (.getGeometry val)))

  ;; ;; Parse SQLXML to a Clojure map representing the XML content
  ;; java.sql.SQLXML
  ;; (result-set-read-column [val _ _]
  ;;   (xml/parse (.getBinaryStream val)))

  )

;; OK, I updated both the SettableParameter and ReadableColumn docs (on master) to try to make things clearer.
;; https://github.com/seancorfield/next-jdbc/blob/master/doc/prepared-statements.md#prepared-statement-parameters
;; https://docs.oracle.com/javase/8/docs/api/java/sql/PreparedStatement.html

;; https://jdbc.postgresql.org/documentation/head/java8-date-time.html

;; https://stackoverflow.com/questions/43259722/java-date-and-timestamp-from-instance-of-zoneddatetime-utc

;; https://github.com/metosin/porsas

;; https://dba.stackexchange.com/questions/198789/is-it-a-bad-practice-to-query-pg-type-for-enums-on-a-regular-basis

;; https://stackoverflow.com/questions/3660787/how-to-list-custom-types-using-postgres-information-schema

;; You should be able to do this via one of the *-adapter builders, since you can pass an arbitrary "column reader" function in, which is invoked with the (current) ResultSet, ResultSetMetadata (returned by the builder), and the column index.
;; (you can't attach anything to the javax.sql.DataSource or java.sql.Connection because they are plain Java objects)
