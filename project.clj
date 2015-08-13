(defproject paren-soup "0.0.1-SNAPSHOT"
  :description "FIXME: write this!"
  :dependencies [[org.clojars.oakes/tools.reader "0.10.0-SNAPSHOT"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"
                  :exclusions [org.clojure/tools.reader]]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [prismatic/schema "0.4.3"]]
  :hooks [leiningen.cljsbuild]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :cljsbuild { 
    :builds {
      :main {
        :source-paths ["src"]
        :compiler {:output-to "resources/public/main.js"
                   :optimizations :simple
                   :pretty-print false
                   :static-fns true
                   :foreign-libs [{:file "src/js/rangy-core.js"
                                   :provides ["rangy.core"]}
                                  {:file "src/js/rangy-textrange.js"
                                   :provides ["rangy.textrange"]}]}
        :jar true}}}
  :main paren-soup.core)
