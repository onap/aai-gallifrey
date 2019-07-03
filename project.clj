(defproject org.onap.aai/gallifrey "1.5.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [com.7theta/utilis "1.0.4"]

                 [http-kit "2.2.0"]
                 [ring/ring-core "1.7.1"]
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
  :prep-tasks ["compile"]
  :repositories [["ecomp-snapshots" {:name "ECOMP Snapshot Repository" :url "https://nexus.onap.org/content/repositories/snapshots/"}]
                 ["onap-releases" {:url "https://nexus.onap.org/content/repositories/releases/"}]]
  :pom-addition [:distributionManagement
                 [:repository
                  [:id "ecomp-releases"]
                  [:name "ECOMP Release Repository"]
                  [:url "https://nexus.onap.org/content/repositories/releases/"]]
                 [:snapshotRepository
                  [:id "ecomp-snapshots"]
                  [:name "ECOMP Snapshot Repository"]
                  [:url "https://nexus.onap.org/content/repositories/snapshots/"]]]
  :pom-plugins [[com.theoryinpractise/clojure-maven-plugin "1.3.13"
                 {:extensions "true"
                  :configuration [:sourceDirectories
                                  [:sourceDirectory "src"]
                                  [:sourceDirectory "prod"]
                                  [:sourceDirectory "test"]]
                  :executions ([:execution [:id "compile"]
                                [:goals ([:goal "compile"])]
                                [:phase "compile"]])}]
                [org.apache.maven.plugins/maven-jar-plugin "2.4"
                 {:configuration [:archive [:manifest
                                            [:addClasspath true]
                                            [:mainClass "gallifrey.server"]
                                            [:classpathPrefix "dependency"]]]}]
                [org.apache.maven.plugins/maven-dependency-plugin "2.8"
                 {:executions ([:execution [:id "copy-dependencies"]
                                [:goals ([:goal "copy-dependencies"])]
                                [:phase "package"]])}]
                [org.apache.maven.plugins/maven-deploy-plugin "3.0.0-M1"]
                [org.apache.maven.plugins/maven-shade-plugin "3.2.0"
                 {:executions ([:execution
                                [:phase "package"]
                                [:goals ([:goal "shade"])]])}]
                [com.spotify/dockerfile-maven-plugin "1.4.4"
                 {:configuration ([:tag "latest"]
                                  [:repository "${docker.push.registry}/onap/gallifrey"]
                                  [:verbose true]
                                  [:serverId "docker-hub"])
                  :executions ([:execution [:id "default"]])}]])
