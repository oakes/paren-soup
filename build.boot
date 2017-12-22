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
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]]
  '[boot-tools-deps.core :refer [deps]])

(task-options!
  pom {:project 'paren-soup
       :version "2.9.4-SNAPSHOT"
       :description "A viewer and editor for ClojureScript"
       :url "https://github.com/oakes/paren-soup"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
  push {:repo "clojars"})

(deftask run []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "dev-resources"})
  (comp
    (deps)
    (serve :dir "target/public")
    (watch)
    (reload :on-jsload 'paren-soup.core/init-all)
    (cljs :source-map true :optimizations :none :compiler-options {:asset-path "paren-soup.out"})
    (target)))

(deftask build []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "prod-resources"})
  (comp (deps) (cljs :optimizations :advanced) (target)))

(deftask local []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (deps) (pom) (jar) (install)))

(deftask deploy []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (deps) (pom) (jar) (push)))

