(defproject shared-deps "0.1.0-SNAPSHOT"
  :description "Centralized declaration of dependencies for multi-projects"
  :url "TBD"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :eval-in-leiningen true
  :dependencies [[io.aviso/toolchest "0.1.2"]
                 ;; This version of dependency is compatible with
                 ;; Clojure < 1.7.
                 [com.stuartsierra/dependency "0.1.1"]
                 [medley "0.6.0"]])
