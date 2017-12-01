(set-env!
  :dependencies '[[adzerk/boot-cljs "1.7.228-2" :scope "test"]
                  [adzerk/boot-reload "0.4.12" :scope "test"]
                  [pandeiro/boot-http "0.7.3" :scope "test"]
                  [org.clojure/test.check "0.9.0" :scope "test"]
                  [org.clojure/clojure "1.8.0" :scope "provided"]
                  ; project deps
                  [mistakes-were-made "1.7.3"]
                  [html-soup "1.5.1"]
                  [cross-parinfer "1.4.2"]
                  [cljsjs/rangy-core "1.3.0-1"]
                  [cljsjs/rangy-textrange "1.3.0-1"]
                  [org.clojure/clojurescript "1.9.946" :scope "provided"]
                  [org.clojure/core.async "0.3.443"]]
  :repositories (conj (get-env :repositories)
                  ["clojars" {:url "https://clojars.org/repo/"
                              :username (System/getenv "CLOJARS_USER")
                              :password (System/getenv "CLOJARS_PASS")}]))

(require
  '[adzerk.boot-cljs :refer [cljs]]
  '[adzerk.boot-reload :refer [reload]]
  '[pandeiro.boot-http :refer [serve]])

(task-options!
  pom {:project 'paren-soup
       :version "2.9.3"
       :description "A viewer and editor for ClojureScript"
       :url "https://github.com/oakes/paren-soup"
       :license {"Public Domain" "http://unlicense.org/UNLICENSE"}}
  push {:repo "clojars"})

(deftask run []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "dev-resources"})
  (comp
    (serve :dir "target/public")
    (watch)
    (reload :on-jsload 'paren-soup.core/init-all)
    (cljs :source-map true :optimizations :none)
    (target)))

(deftask build []
  (set-env! :source-paths #{"src"} :resource-paths #{"resources" "prod-resources"})
  (comp (cljs :optimizations :advanced) (target)))

(deftask local []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (pom) (jar) (install)))

(deftask deploy []
  (set-env! :source-paths #{} :resource-paths #{"src" "resources" "prod-resources"})
  (comp (pom) (jar) (push)))

