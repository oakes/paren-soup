(defmulti task first)

(defmethod task :default
  [_]
  (let [all-tasks  (-> task methods (dissoc :default) keys sort)
        interposed (->> all-tasks (interpose ", ") (apply str))]
    (println "Unknown or missing task. Choose one of:" interposed)
    (System/exit 1)))

(require
  '[cljs.build.api :as api])

(defmethod task "build"
  [_]
  (println "Building paren-soup.js")
  (api/build "src" {:main          'paren-soup.prod
                    :optimizations :advanced
                    :output-to     "target-cljs/public/paren-soup.js"
                    :output-dir    "target/public/paren-soup.out"})
  (println "Building paren-soup-compiler.js")
  (api/build "src" {:main               'paren-soup.compiler
                    :optimizations      :simple
                    :output-to          "target-cljs/public/paren-soup-compiler.js"
                    :output-dir         "target/public/paren-soup-compiler.out"
                    :static-fns         true
                    :optimize-constants true})
  (println "Building paren-soup-with-compiler.js")
  (api/build "src" {:main               'paren-soup.core
                    :optimizations      :simple
                    :output-to          "target-cljs/public/paren-soup-with-compiler.js"
                    :output-dir         "target/public/paren-soup-with-compiler.out"
                    :static-fns         true
                    :optimize-constants true}))

(require
  '[leiningen.core.project :as p :refer [defproject]]
  '[leiningen.install :refer [install]]
  '[leiningen.deploy :refer [deploy]])

(defn read-project-clj []
  (p/ensure-dynamic-classloader)
  (-> "project.clj" load-file var-get))

(defn read-deps-edn [aliases-to-include]
  (let [{:keys [paths deps aliases]} (-> "deps.edn" slurp clojure.edn/read-string)]
    (->> (select-keys aliases aliases-to-include)
          vals
          (mapcat :extra-deps)
          (into deps)
          (reduce
            (fn [deps [artifact info]]
              (if-let [version (:mvn/version info)]
                (conj deps
                  (transduce cat conj [artifact version]
                    (select-keys info [:scope :exclusions])))
                deps))
            []))))

(defmethod task "install"
  [_]
  (-> (read-project-clj)
      (dissoc :middleware)
      (assoc :dependencies (read-deps-edn []))
      p/init-project
      install)
  (System/exit 0))

(defmethod task "deploy"
  [_]
  (-> (read-project-clj)
      (dissoc :middleware)
      (assoc :dependencies (read-deps-edn []))
      p/init-project
      (deploy "clojars"))
  (System/exit 0))

;; entry point

(task *command-line-args*)

