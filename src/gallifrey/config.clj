(ns gallifrey.config
  (:require [integrant.core :as ig]))

(defn config
  [app-config]
  (let [conf {:gallifrey/store
              {:db-server (:db-server app-config)
               :db-name "gallifrey"}

              :gallifrey/handler
              {:store (ig/ref :gallifrey/store)}

              :gallifrey/http-server
              {:port (:http-port app-config)
               :handler (ig/ref :gallifrey/handler)}}]
    (ig/load-namespaces conf)
    conf))
