(defn read-deps-edn [aliases-to-include]
  (let [{:keys [paths deps aliases]} (-> "deps.edn" slurp clojure.edn/read-string)
        deps (->> (select-keys aliases aliases-to-include)
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
                    []))]
    {:dependencies deps
     :source-paths (set paths)
     :resource-paths (set paths)}))

(let [{:keys [source-paths resource-paths dependencies]} (read-deps-edn [])]
  (set-env!
    :source-paths source-paths
    :resource-paths resource-paths
    :dependencies (into '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                          [adzerk/boot-reload "0.5.2" :scope "test"]
                          [org.clojure/test.check "0.9.0" :scope "test"]
                          [ring "1.6.3" :scope "test"]
                          [javax.xml.bind/jaxb-api "2.3.0" :scope "test"] ; necessary for Java 9 compatibility
                          [orchestra "2017.11.12-1" :scope "test"]]
                        dependencies)
    :repositories (conj (get-env :repositories)
                    ["clojars" {:url "https://clojars.org/repo/"
                                :username (System/getenv "CLOJARS_USER")
                                :password (System/getenv "CLOJARS_PASS")}])))

(require
  '[clojure.java.io :as io]
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[ring.adapter.jetty :refer [run-jetty]]
  '[ring.middleware.file :refer [wrap-file]]
  '[ring.util.response :refer [not-found]])

(task-options!
  pom {:project 'paren-soup
       :version "2.13.0"
       :description "A viewer and editor for ClojureScript"
       :url "https://github.com/oakes/paren-soup"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
  push {:repo "clojars"})

(deftask run []
  (set-env!
    :dependencies #(into (set %) (:dependencies (read-deps-edn [:cljs])))
    :resource-paths #(conj % "resources" "dev-resources"))
  (comp
    (with-pass-thru _
      (.mkdirs (io/file "target/public"))
      (-> (fn [{:keys [uri]}]
            (if (= uri "/")
              {:status 200
               :headers {"Content-Type" "text/html"}
               :body (slurp "target/public/index.html")}
              (not-found "File not found")))
           (wrap-file "target/public")
           (run-jetty {:port 3000 :join? false})))
    (watch)
    (reload :asset-path "public" :on-jsload 'paren-soup.core/init-all)
    (cljs)
    (target)))

(deftask build []
  (set-env!
    :dependencies #(into (set %) (:dependencies (read-deps-edn [:cljs])))
    :resource-paths #(conj % "resources" "prod-resources"))
  (comp (cljs) (target)))

(deftask local []
  (comp (pom) (jar) (install)))

(deftask deploy []
  (comp (pom) (jar) (push)))

