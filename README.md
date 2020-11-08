# Coerce

## Purpose

Provide useful (automatic) type coercions between Clojure and database(s)

Currently focused on JDBC and Postgres/PostGIS

(PostGIS support not currently implemented)

## Usage

Include `[com.dcj/coerce "some-version"]` in your project.clj

Require `[coerce.jdbc.pg]` in your app/namespace

## Opinionated

Current opinions:

* [`next.jdbc`](https://github.com/seancorfield/next-jdbc) is the JDBC library of choice
* [`clojure.java-time`](https://github.com/dm3/clojure.java-time) is the time library of choice
* Add [ThreeTen-Extra](https://www.threeten.org/threeten-extra/) to get Interval
* Use `java.time.ZonedDateTime` for SQL `timestamp` and Postgres `timestamptz`, and vice versa
* Prefer `timestamptz` whenever possible
* `UTC` is the timezone of choice, and is the default and assumed
* Use `org.threeten.extra.Interval` for Postgres `tstzrange` (preferred) and `tsrange`
* [Factual/geo](https://github.com/Factual/geo) is the geospatial library of choice
* [Uber's H3](https://eng.uber.com/h3/) is the spatial index of choice, see also: [`h3-pg`](https://github.com/bytesandbrains/h3-pg) and [`pgh3`](https://github.com/dlr-eoc/pgh3)

## Future Work

See `TODO.org` in the base of this repo

## Credits

Virtually all of the Postgres/PostGIS coercions were ported directly from [Remod Oy's clj-postgresql](https://github.com/remodoy/clj-postgresql) and [Aleh Atsman's fork](https://github.com/atsman/clj-postgresql), without their efforts this would not exist.

[Sean Corfield](https://corfield.org) developed next.jdbc, and answered numerous questions as I attempted to modify the existing clj-postgresql coercions to next.jdbc.

I am extremely grateful to Oy, Atsman, and Corfield for their generous contributions.

## License

[Given this is a modification of Oy's original work, his license applies](https://github.com/remodoy/clj-postgresql#license)
