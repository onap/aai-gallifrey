(ns gallifrey.server
  (:require [gallifrey.config :refer [config]]
            [gallifrey.handler :refer [handler]]
            [config.core :refer [env]]
            [org.httpkit.server :refer [run-server]]
            [integrant.core :as ig])
  (:gen-class))

(defn -main [& args]
  (let [port (Integer/parseInt (or (env :http-port) "8081"))]
    (println "Listening on port" port)
    (ig/init (config {:db-server {:host (or (env :db-host) "rethinkdb")
                                  :port 28015}
                      :http-port port}))))
