(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application."
  (:require [gallifrey.config :refer [config]]
            [gallifrey.handler :refer [handler]]
            [gallifrey.store :as gs]

            [integrant.core :as ig]

            [integrant.repl :refer [clear go halt init reset reset-all]]
            [integrant.repl.state :refer [system]]

            [clojure.tools.namespace.repl :refer [refresh refresh-all disable-reload!]]
            [clojure.repl :refer [apropos dir doc find-doc pst source]]
            [clojure.test :refer [run-tests run-all-tests]]
            [clojure.pprint :refer [pprint]]
            [clojure.reflect :refer [reflect]]))

(disable-reload! (find-ns 'integrant.core))

(integrant.repl/set-prep! (constantly (config {:db-server {:host "localhost" :port 28015}
                                               :http-port 3449})))
