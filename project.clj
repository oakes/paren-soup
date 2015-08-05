(defproject paren-soup "0.0.1-SNAPSHOT"
  :description "FIXME: write this!"
  :dependencies [[org.clojars.oakes/tools.reader "0.10.0-SNAPSHOT"]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.28"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [prismatic/schema "0.4.3"]
                 [ring "1.4.0"]]
  :hooks [leiningen.cljsbuild]
  :source-paths ["src/clj"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :cljsbuild { 
    :builds {
      :main {
        :source-paths ["src/cljs"]
        :compiler {:output-to "resources/public/main.js"
                   :optimizations :advanced
                   :pretty-print false
                   :foreign-libs [{:file "src/js/rangy-core.js"
                                   :provides ["rangy.core"]}
                                  {:file "src/js/rangy-textrange.js"
                                   :provides ["rangy.textrange"]}]}
        :jar true}}}
  :aot [paren-soup.core]
  :main paren-soup.core
  :ring {:handler paren-soup.core/app})
