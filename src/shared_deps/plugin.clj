(ns shared-deps.plugin
  "Modifies the project by reading the :dependency-categories key
  and merging in the appropriate dependencies specified in the
  dependencies.edn file."
  (:require [leiningen.core.main :as main]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [clojure.edn :as edn]
            [medley.core :as medley])
  (:import (java.io PushbackReader)))


(defn- find-dependencies-file [{root-path :root
                                project-name :name}]
  (main/debug (format "Seaching for shared dependencies of project %s, starting in `%s'." project-name root-path))
  (loop [dir (io/file root-path)]
    (cond-let
      (nil? dir)
      (main/warn "Unable to find dependencies.edn file in directory `%s', or any parent directory." root-path)

      [f (io/file dir "dependencies.edn")]

      (.exists f)
      f

      :else
      (recur (.getParent dir)))))

(defn- read-dependencies-file* [file]
  (main/debug (format "Reading shared dependencies from `%s'." file))
  (with-open [s (io/reader file)]
    ;; Allow the values for each category to be a vector, which is expanded
    ;; to a map with key :dependencies
    (medley/map-vals
      #(if (vector? %)
        {:dependencies %}
        %)
      (-> s
          PushbackReader.
          edn/read))))

(def ^:private read-dependencies-file (memoize read-dependencies-file*))

(defn- read-shared-dependencies [project]
  (when-let [dependencies-file (find-dependencies-file project)]
    (read-dependencies-file dependencies-file)))


(defn- apply-category
  [shared-dependencies project category]
  ;; TODO: warning when category not found
  ;; Using update-in for compatibility with older version of Clojure.
  ;; Convert it to update at some point in future.
  (update-in project [:dependencies]
             into (get-in shared-dependencies [category :dependencies])))

(defn- apply-categories
  [project categories shared-dependencies]
  (main/debug (format "Applying categories %s to project %s."
                      (str/join ", " categories)
                      (:name project)))
  ;; TODO: transitive categories in correct order
  ;; Since order counts, and what we get is usually some flavor of list (or lazy
  ;; seq), convert dependencies into a vector first.
  (let [project' (update-in project [:dependencies] vec)]
    (-> (reduce (partial apply-category shared-dependencies) project' categories)
        ;; There's any number of ways that we can end up with duplicated lines
        ;; including that the middleware is invoked for each profile.
        ;; So we clean the dependencies.
        (update-in [:dependencies]
                   (comp vec distinct)))))

(defn- extend-project-with-categories [project categories]
  (or (if-let [shared-dependencies (read-shared-dependencies project)]
        (apply-categories project categories shared-dependencies))
      ;; Any prior stage can return nil on a failure, and we'll just return
      ;; the project unchanged.
      project))

;; Keyed on project name, will be true once the warning has
;; been output (middleware gets called multiple times even in the same
;; project, based on the application of profiles).
(def ^:private warning-output (atom {}))

(defn middleware
  [project]
  (if-let [categories (-> project :dependency-categories seq)]
    (extend-project-with-categories project categories)
    (let [project-name (:name project)]
      (when-not (get @warning-output project-name)
        (swap! warning-output assoc project-name true)
        (main/warn (format
                     "Project %s should specify a list of dependency categories in its :dependency-categories key."
                     project-name)))
      project)))