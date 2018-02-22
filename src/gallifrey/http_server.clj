(ns gallifrey.http-server
  (:require [org.httpkit.server :refer [run-server]]
            [integrant.core :as ig]))

(defmethod ig/init-key :gallifrey/http-server  [_ {:keys [port handler]}]
  (run-server handler {:port port}))

(defmethod ig/halt-key! :gallifrey/http-server  [_ server]
  (server :timeout 100))
