(defproject clanhr/shared-deps "0.2.6"
  :description "Centralized declaration of dependencies for multi-projects"
  :url "https://github.com/clanhr/shared-deps"
  :license {:name "Apache Software License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :eval-in-leiningen true
  :dependencies [[io.aviso/toolchest "0.1.3"]
                 [dorothy "0.0.6"]
                 ;; This version of dependency is compatible with
                 ;; Clojure < 1.7.
                 [com.stuartsierra/dependency "0.1.1"]
                 [medley "0.6.0"]])
