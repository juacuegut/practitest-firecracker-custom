(ns build
  (:require
    [clojure.java.io :as io]
    [clojure.tools.build.api :as b]))

(def lib 'practitest/practitest-firecracker)

; Create new tag v<Major>.<Minor> when starting working on new major/minor version branch
(def major-version 2)
(def minor-version 2)
(def path-version 13)
(def build-number 1)

(def version (format "%s.%s.%s.%s" major-version minor-version path-version build-number))
(def class-dir "target/classes")
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

;; delay to defer side effects (artifact downloads)
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (println "Cleaning directories")
  (b/delete {:path "target"}))

(defn uber [_]
  (println "Building uberjar for" version)
  (clean nil)
  (b/copy-dir {:src-dirs ["src"]
               :target-dir class-dir})
  (b/copy-dir {:src-dirs ["resources"]
               :target-dir class-dir})
  (println "Compiling clojure code")
  (b/compile-clj {:basis @basis
                  :ns-compile '[practitest-firecracker.core]
                  :class-dir class-dir})
  (println "Packing JAR file")
  (spit (io/file class-dir "practitest_firecracker" "firecracker_version.txt")
        version)
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis @basis
           :manifest {"Implementation-Version" version}
           :main 'practitest-firecracker.core}))
