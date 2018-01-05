(set-env!
  :dependencies '[[adzerk/boot-cljs "2.1.4" :scope "test"]
                  [adzerk/boot-reload "0.5.2" :scope "test"]
                  [pandeiro/boot-http "0.7.3" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  [seancorfield/boot-tools-deps "0.1.4" :scope "test"]]
  :repositories (conj (get-env :repositories)
                  ["clojars" {:url "https://clojars.org/repo/"
                              :username (System/getenv "CLOJARS_USER")
                              :password (System/getenv "CLOJARS_PASS")}]))

(require
  '[clojure.edn :as edn]
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]]
  '[boot-tools-deps.core :refer [deps]])

(task-options!
  pom {:project 'paren-soup
       :version "2.9.5-SNAPSHOT"
       :description "A viewer and editor for ClojureScript"
       :url "https://github.com/oakes/paren-soup"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}
       :dependencies (->> "deps.edn"
                          slurp
                          edn/read-string
                          :deps
                          (reduce
                            (fn [deps [artifact info]]
                              (if-let [version (:mvn/version info)]
                                (conj deps
                                  (transduce cat conj [artifact version]
                                    (select-keys info [:scope :exclusions])))
                                deps))
                            []))}
  push {:repo "clojars"})

(deftask run []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "dev-resources"})
  (comp
    (deps :aliases [:cljs])
    (serve :dir "target/public")
    (watch)
    (reload :on-jsload 'paren-soup.core/init-all)
    (cljs :source-map true :optimizations :none :compiler-options {:asset-path "paren-soup.out"})
    (target)))

(deftask build []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "prod-resources"})
  (comp (deps :aliases [:cljs]) (cljs :optimizations :advanced) (target)))

(deftask local []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (pom) (jar) (install)))

(deftask deploy []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (pom) (jar) (push)))

