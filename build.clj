(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'clj.template/lib)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def main 'main)

(defn clean
  "Cleans the target path."
  [_]
  (b/delete {:path "target"}))

(defn compile-cljs []
  (let [{:keys [exit]} (b/process {:command-args ["npx" "shadow-cljs" "release" "app"]})]
    (when-not (= 0 exit)
      (throw (ex-info "Failed to compile cljs" {:exit exit})))))

(defn jar [_]
  (compile-cljs)
  (b/copy-dir {:src-dirs ["src/clj" "resources"]
               :target-dir class-dir})
  ; For xt-version
  (b/copy-file {:src "deps.edn"
                :target (str class-dir "/deps.edn")})
  (b/compile-clj {:basis basis
                  :src-dirs ["src/clj"]
                  :class-dir class-dir
                  :bindings {#'clojure.core/*assert* false}})
  (b/uber {:class-dir class-dir
           :uber-file jar-file
           :main main
           :basis basis
           :conflict-handlers
           {"org/apache/arrow/vector/.*" :overwrite
            ; Defaults
            "^data_readers.clj[c]?$" :data-readers
            "^META-INF/services/.*" :append
            "(?i)^(META-INF/)?(COPYRIGHT|NOTICE|LICENSE)(\\.(txt|md))?$" :append-dedupe
            :default :ignore}}))


(comment
  (clean nil)
  (jar nil))
