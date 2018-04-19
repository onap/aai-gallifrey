(defproject gallifrey "0.4.0"
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [com.7theta/utilis "1.0.4"]

                 [http-kit "2.2.0"]
                 [ring/ring-core "1.6.3"]
                 [ring/ring-defaults "0.3.1"]
                 [ring/ring-anti-forgery "1.1.0"]
                 [compojure "1.6.0"]
                 [liberator "0.15.1"]
                 [cheshire "5.7.1"]

                 [com.apa512/rethinkdb "0.15.26"]
                 [inflections "0.13.0"]
                 [clj-time "0.14.2"]

                 [integrant "0.6.2"]
                 [clojure-future-spec "1.9.0-beta4"]
                 [metrics-clojure "2.10.0"]
                 [metrics-clojure-ring "2.10.0"]
                 [yogthos/config "0.9"]]
  :min-lein-version "2.5.3"
  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[ring/ring-devel "1.6.3"]
                                  [integrant/repl "0.2.0"]]}
             :uberjar {:source-paths ["prod"]
                       :main gallifrey.server
                       :uberjar-name "gallifrey.jar"}}
  :prep-tasks ["compile"])
