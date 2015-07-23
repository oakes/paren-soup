(set-env!
  :source-paths   #{"src/clj" "src/cljs"}
  :resource-paths #{"resources"}
  :dependencies '[
    [adzerk/boot-cljs          "0.0-3308-0"      :scope "test"]
    [adzerk/boot-cljs-repl     "0.1.10-SNAPSHOT" :scope "test"]
    [adzerk/boot-reload        "0.3.1"           :scope "test"]
    [pandeiro/boot-http        "0.6.3-SNAPSHOT"  :scope "test"]
    [org.clojure/clojure       "1.7.0"]
    [org.clojure/clojurescript "0.0-3308"]
    [org.clojure/tools.reader "0.10.0-alpha1"]
    [prismatic/schema "0.4.3"]
    [ring "1.4.0"]])

(require
  '[adzerk.boot-cljs      :refer [cljs]]
  '[adzerk.boot-cljs-repl :refer [cljs-repl start-repl]]
  '[adzerk.boot-reload    :refer [reload]]
  '[pandeiro.boot-http    :refer [serve]])

(deftask dev []
  (set-env! :source-paths #{"src/clj" "src/cljs" "test"})
  (comp (serve :dir "target/public")
        (watch)
        ;(speak)
        (reload :on-jsload 'paren-soup.core/init-with-validation!)
        (cljs-repl)
        (cljs :source-map true :optimizations :none)))

(deftask build []
  (set-env! :source-paths #{"src/clj" "src/cljs"})
  (comp
    (cljs :optimizations :advanced)
    (aot :namespace '#{paren-soup.core})
    (pom :project 'paren-soup
         :version "1.0.0")
    (uber)
    (jar :main 'paren-soup.core)))
