(ns lein-tools-deps.plugin
  (:require [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.reader :as reader]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [leiningen.core.project :as p]
            [leiningen.core.main :as lein])
  (:import (java.io File)))

;; load extensions
(require 'clojure.tools.deps.alpha.extensions.deps)
(require 'clojure.tools.deps.alpha.extensions.git)
(require 'clojure.tools.deps.alpha.extensions.local)
(require 'clojure.tools.deps.alpha.extensions.maven)

(defn usr-local-bin-clojure-executable
  "Returns the path to the clojure under /usr/local/bin, if it exists."
  []
  (let [executable (io/file File/separator "usr" "local" "bin" "clojure")]
    (when (.exists executable)
      (.getAbsolutePath executable))))

(def clojure-executables (remove nil? ["clojure" (usr-local-bin-clojure-executable)]))

(defn- scrape-clojure-env
  []
  (let [scrape  (fn [clojure-executable]
                  (try
                    (let [{:keys [out exit] :as results} (shell/sh clojure-executable "-Sdescribe")]
                      (if (zero? exit)
                        (read-string out)
                        {::fail results}))
                    (catch Exception e
                      {::fail e})))
        results (reduce (fn [acc clojure-executable]
                          (let [scraped (scrape clojure-executable)]
                            (if (contains? scraped ::fail)
                              (conj acc (::fail scraped))
                              (reduced scraped))))
                  []
                  clojure-executables)]
    (if (vector? results)
      (throw (ex-info "Unable to locate Clojure's edn files" {:paths-tried clojure-executables
                                                              :results     results}))
      results)))

(def clojure-env
  "Returns a map describing the environment known to clj/clojure:
  {:config-files [ ... ]}"
  (memoize scrape-clojure-env))

(defn make-dep-loc-lookup
  "Returns a function mapping from a loc(ation)
  keyword (either :system, :home or :project) to an absolute file
  location.  If the value is a string it is returned as is."
  []
  (let [[system-deps home-deps project-deps] (:config-files (clojure-env))]
    (fn [i]
      (if (string? i)
        i
        ({:system system-deps
          :home home-deps
          :project project-deps} i)))))

(defn canonicalise-dep-locs [dep-refs]
  (let [location->dep-path (make-dep-loc-lookup)]
    (->> dep-refs
         (map #(location->dep-path %))
         (map io/file))))

(defn read-all-deps [deps-files]
  (-> deps-files
      reader/read-deps))

(defmulti leinize (fn [[dep-key dep-val]]
                    (:deps/manifest dep-val)))

(defmethod leinize :mvn [[artifact info]]
  ;; Thanks to @seancorfield and boot-tools-deps for this snippet
  (transduce cat conj [artifact (:mvn/version info)]
             (select-keys info
                          [:classifier
                           :extension
                           :exclusions
                           :scope])))

(defmethod leinize :deps [[artifact info]]
  (:paths info))

(defn filter-by-manifest [manifest-type tdeps]
  (filter (fn [[artifact info]]
            (= manifest-type (:deps/manifest info)))
          tdeps))

(defn lein-dependencies [tdeps]
  {:dependencies (->> tdeps
                      (filter-by-manifest :mvn)
                      (mapv leinize))})

(defn lein-source-paths [merged-deps tdeps]
  {:source-paths (->> tdeps
                      (filter-by-manifest :deps)
                      (mapv leinize)
                      (apply concat)
                      (into (:paths merged-deps)))})

(defn resolve-deps
  "Takes a seq of java.io.File objects pointing to deps.edn files
  and merges them all before resolving their dependencies.

  Returns a {:dependencies [coordinates]} datastructure suitable for
  meta-merging into a lein project map."
  [deps]
  (let [all-deps (filter #(.exists %) deps)
        merged-deps (read-all-deps all-deps)
        tdeps-map (deps/resolve-deps merged-deps {})]
    (merge (lein-dependencies tdeps-map)
           (lein-source-paths merged-deps tdeps-map))))

(defn loc-or-string? [l]
  (or (#{:system :home :project} l) (string? l)))

(defn middleware
  "Inject relevant keys from deps.edn files into the leiningen project map."
  [{deps-files :tools/deps :as project}]
  (if (seq deps-files)
    (if (every? loc-or-string? deps-files)
      (->> deps-files
           canonicalise-dep-locs
           resolve-deps
           (merge project))
      (do (lein/warn  "Every element in :tools/deps must either be a file-path string or one of the locations :system, :project, or :home.")
          (lein/exit 1)))
    project))

(comment
  (read-all-deps (canonicalise-dep-locs [:system :home "example/deps.edn"]))

  (resolve-deps (canonicalise-dep-locs [:system :home "example/deps.edn" "foo"])))
