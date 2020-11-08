(defproject com.dcj/coerce

  "0.1.0-SNAPSHOT"

  :dependencies [[org.clojure/clojure "1.10.1"]

                 [clojure.java-time "0.3.2"]

                 [org.threeten/threeten-extra "1.5.0"]

                 [org.postgresql/postgresql "42.2.18"]

                 [net.postgis/postgis-jdbc "2.5.0"]

                 [seancorfield/next.jdbc "1.1.588"]

                 ;; [prismatic/plumbing "0.5.5"]

                 [factual/geo "3.0.1"]

                 [cheshire "5.10.0"]]

  :repositories {"snapshots"
                 {:url "https://repo.deps.co/aircraft-noise/snapshots"
                  :username :env/deps_key
                  :password :env/deps_secret}}

  )
