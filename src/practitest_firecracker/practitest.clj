(ns practitest-firecracker.practitest
  (:require
    [clojure.set :refer [difference]]
    [clojure.string :as string]
    [clojure.tools.logging :as log]
    [practitest-firecracker.utils :refer [print-run-time test-need-update? pformat replace-map replace-keys transform-keys]]
    [practitest-firecracker.api :as api]
    [practitest-firecracker.const :refer [max-test-ids-bucket-size]]
    [practitest-firecracker.query-dsl :as query-dsl]
    [practitest-firecracker.eval :as eval]
    [clojure.pprint :as pprint]))

(defn find-sf-testset [client [project-id display-action-logs] options testset-name]
  (let [testset (api/ll-find-testset client [project-id display-action-logs] testset-name)]
    (when testset
      (eval/update-sf-testset client options testset-name testset (read-string (:id testset))))))

(defn create-testsets [client {:keys [project-id display-action-logs] :as options} xml]
  (doall
    (for [sf-test-suites xml]
      (let [testset-name (or (:name (:attrs sf-test-suites) (:name sf-test-suites)))
            testset (or (find-sf-testset client [project-id display-action-logs] options testset-name)
                        (eval/create-sf-testset client options (:test-cases sf-test-suites) testset-name))]
        {(:id testset) (:test-list sf-test-suites)}))))

(defn group-tests [testsets _ options]
  (let [ts-id-test-id-num-instances (into {} (map (fn [[k v]]
                                                    {k
                                                     (frequencies (map (fn [test] (:id test)) v))})
                                                  (into {} testsets)))
        testset-id-to-id (map (fn [[k v]]
                                {k (set (map :id v))})
                              (into {} testsets))
        xml-tests (flatten (map val (into {} testsets)))
        all-tests (doall (into {} (for [test xml-tests]
                                    {(:id test) test})))]
    [all-tests xml-tests testset-id-to-id ts-id-test-id-num-instances]))

(defn value-get [existing-cases xml-cases]
  (conj (into [] existing-cases) (into [] xml-cases)))

(defn connect-maps [map1 map2]
  (reduce (fn [result key1]
            (assoc result key1 [(merge (into {} (map1 key1)) (into {} (map2 key1)))]))
          {}
          (distinct (into (keys map1) (keys map2)))))

(defn update-steps [old-tests test-id-to-cases test-name-to-params options]
  (if (:use-test-step options)
    (map
      (fn [t]
        (let [test-id (Integer/parseInt (:id (last t)))
              test-name (first t)
              params     (get test-name-to-params test-name)
              test-test-cases (get test-id-to-cases test-id)
              t-test-cases (filter
                             (fn [case]
                               (not (and (:only-failed-steps options) (= "" (:failure-detail case)))))
                             (:test-cases (second t)))]
          (assoc-in t
                    [1 :test-cases]
                    (map first
                         (map val
                              (let [group-by-test (group-by
                                                    (fn [case]
                                                      (reduce (fn [p param]
                                                                (or p
                                                                    (replace-map
                                                                      (if (= (:match-step-by options) "description")
                                                                        (:description case)
                                                                        (:pt-test-step-name case))
                                                                      (replace-keys param))))
                                                              false params)) test-test-cases)
                                    group-by-t (group-by
                                                 (fn [case]
                                                   (reduce (fn [p param]
                                                             (or p
                                                                 (replace-map
                                                                   (if (= (:match-step-by options) "description")
                                                                     (eval/sf-test-case->pt-step-description options case)
                                                                     (eval/sf-test-case->pt-step-name options case))
                                                                   (replace-keys param))))
                                                           false params)) t-test-cases)]
                                (connect-maps
                                  group-by-t
                                  group-by-test))))))) old-tests)
    old-tests))

(defn translate-step-attributes [attributes]
  {:pt-test-step-name (:name attributes)
   :description       (:description attributes)
   :position          (:position attributes)
   :attributes attributes})

(defn create-or-update-tests [[all-tests org-xml-tests testset-id-to-id ts-id-test-id-num-instances] client {:keys [project-id display-action-logs display-run-time use-test-step] :as options} start-time]
  (let [new-tests (into [] (eval/group-test-ids (vals all-tests) options))
        results (doall
                  (flatten
                    (into []
                          (pmap
                            (fn [new-tests-part]
                              (api/ll-find-tests client [project-id display-action-logs] new-tests-part)) (partition-all 20 new-tests)))))

        xml-tests (doall (for [res results]
                           [(:id (:query res)) (get all-tests (:id (:query res))) (first (:data (:tests res)))]))

        test-ids (->> results
                      (map #(get-in % [:tests :data]))
                      (flatten)
                      (map :id)
                      (map #(Integer/parseInt %)))

        test-cases (->> test-ids
                        (partition-all 20)
                        (pmap
                          (fn [test-ids]
                            (api/ll-test-steps client project-id (string/join "," test-ids))))
                        (into [])
                        (flatten))

        test-id-to-cases (into {}
                               (map (fn [[grp-key values]]
                                      {grp-key (map #(translate-step-attributes (:attributes %)) values)})
                                    (group-by (fn [x] (:test-id (:attributes x))) test-cases)))

        log (if display-run-time (print-run-time "Time - after find all tests: %d:%d:%d" start-time) nil)
        nil-tests (filter #(nil? (last %)) xml-tests)
        old-tests (filter #(not (nil? (last %))) xml-tests)

        tests-after (if (seq nil-tests)
                      ;; create missing tests and add them to the testset
                      (let [new-tests (pmap (fn [[test-id test-suite _]]
                                              [test-id test-suite (eval/create-sf-test client options test-suite)])
                                            nil-tests)]
                        new-tests)
                      ())
        log (if display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time) nil)
        new-all-tests (concat tests-after old-tests)]
    (when (seq old-tests)
      ;; update existing tests with new values
      (doall (map (fn [[_ test-suite test]]
                    (when (test-need-update? test-suite test)
                      (eval/update-sf-test client options test-suite test)))
                  old-tests))
      (when display-run-time (print-run-time "Time - after update tests: %d:%d:%d" start-time)))
    [new-all-tests org-xml-tests testset-id-to-id ts-id-test-id-num-instances test-id-to-cases tests-after]))

;; API returns mangled parameters because of rails key transformation, so for lookup purposes
;; we're trying to mimic what rails does to parameter keys
(defn- transform-rails-key [key]
  ;; TODO: https://apidock.com/rails/v7.1.3.2/ActiveSupport/Inflector/underscore
  (keyword (string/lower-case (str key))))

(defn- transform-rails-parameters [params-map]
  (transform-keys params-map transform-rails-key))

(defn get-parameters-map [test pt-instance-params]
  (transform-rails-parameters
    (if (= "?outline-params" pt-instance-params)
      ;; Special handling for BDD params - return bare row from outline example table
      (or (:outline-params-map test)
          ;; For specflow tests - use parameters map from XML report as well (if not outline is detected)
          (:parameters-map test))
      (zipmap
        (iterate inc 1)
        (into []
              (map
                (fn [param]
                  (string/trim param))
                (filter not-empty
                        (string/split
                          (query-dsl/eval-query
                            test
                            (query-dsl/read-query pt-instance-params))
                          #"\|"))))))))

(defn create-instances [[all-tests org-xml-tests testset-id-to-id ts-id-test-id-num-instances test-id-to-cases tests-after]
                        client
                        {:keys [project-id display-action-logs display-run-time pt-instance-params] :as options} start-time]
  (when display-action-logs (log/infof "pt-instance-params: %s" pt-instance-params))
  (let [all-test-ids (map (fn [test] (:id (last test))) all-tests)
        testid-test (into {} (map (fn [test] {(first test) test}) all-tests))

        test-id-testid (into {} (map (fn [test] {(query-dsl/parse-int (:id (last test))) (first test)}) all-tests))
        testid-params (when (not-empty pt-instance-params)
                        (into {}
                              (map
                                (fn [test]
                                  (let [split-params (get-parameters-map (second test) pt-instance-params)]
                                    {(Integer/parseInt (:id (last test)))
                                     (zipmap (iterate inc 1) split-params)}))
                                all-tests)))

        log (when display-action-logs (log/infof "testid-params: %s" testid-params))

        testset-ids (map (fn [testset] (first (first testset))) testset-id-to-id)

        ts-ids (string/join "," testset-ids)

        instances (mapcat (fn [test-ids-bucket]
                            (api/ll-testset-instances client
                                                      [project-id display-action-logs]
                                                      ts-ids
                                                      (string/join "," test-ids-bucket)))
                          (partition-all max-test-ids-bucket-size all-test-ids))

        testid-to-params (when (not-empty pt-instance-params)
                           (into {}
                                 (map
                                   (fn [[key tests]]
                                     {key
                                      (into []
                                            (remove nil?
                                                    (map
                                                      (fn [test]
                                                        (get-parameters-map test pt-instance-params)) tests)))})
                                   (group-by
                                     #(eval/sf-test-suite->pt-test-id options %)
                                     org-xml-tests))))

        test-id-params (when
                         (not-empty pt-instance-params)
                         (into
                           #{}
                           (map
                             (fn [test]
                               [(eval/sf-test-suite->pt-test-id options test)
                                (get-parameters-map test pt-instance-params)])
                             org-xml-tests)))

        filter-instances (if (not-empty pt-instance-params)
                           (filter
                             (fn [instance]
                               (let
                                 [{:keys [name bdd-parameters parameters]} (:attributes instance)
                                  obj-test (get testid-test name)
                                  test-type (:test-type (:attributes (last obj-test)))
                                  params (if (= test-type "BDDTest") bdd-parameters parameters)
                                  params (if (map? params)
                                           params
                                           ;; Vector case - not sure when it happens, convert to map
                                           (zipmap (iterate inc 1) params))]
                                 (if (seq params)
                                   (contains? test-id-params [name params])
                                   ;; Do not filter if no parameters is set
                                   true)))
                             instances)
                           instances)

        ts-id-instance-num (into {} (map (fn [testset-id-name]
                                           {(first (first testset-id-name))
                                            (into {}
                                                  (map
                                                    (fn [test-id]
                                                      {(read-string (:id (last (get testid-test test-id))))
                                                       (get (get ts-id-test-id-num-instances (first (first testset-id-name))) test-id)})
                                                    (last (last testset-id-name))))})
                                         testset-id-to-id))
        ts-id-instances (group-by (fn [inst] (get-in inst [:attributes :set-id])) filter-instances)

        missing-instances (into {}
                                (doall
                                  (for [ts-id (into () testset-ids)]
                                    {ts-id
                                     (merge-with -
                                                 (get ts-id-instance-num ts-id)
                                                 (frequencies (vec (map #(get-in % [:attributes :test-id])
                                                                        (get ts-id-instances (read-string ts-id))))))})))

        existing-instance (when (not-empty pt-instance-params)
                            (into {}
                                  (map
                                    (fn [[key tests]]
                                      {key
                                       (into []
                                             (map
                                               (fn [test]
                                                 (conj (get-in test [:attributes :bdd-parameters])
                                                       (get-in test [:attributes :parameters]))) tests))})
                                    (group-by
                                      (fn [inst]
                                        (get-in inst [:attributes :name]))
                                      filter-instances))))

        new-testid-to-params (into {}
                                   (map
                                     (fn [test-id]
                                       {test-id
                                        (into []
                                              (difference
                                                (set (get testid-to-params test-id))
                                                (set (get existing-instance test-id))))})
                                     (keys testid-to-params)))
        tests-with-steps (update-steps all-tests test-id-to-cases testid-to-params options)
        all-tests (concat tests-after tests-with-steps)
        make-instances (flatten (api/make-instances missing-instances new-testid-to-params test-id-testid))
        test-by-id (group-by (fn [test] (read-string (:id (last test)))) all-tests)
        new-intstances (flatten (for [instances-part (partition-all 100 (shuffle make-instances))]
                                  (api/ll-create-instances client [project-id display-action-logs] instances-part)))
        all-intstances (into [] (concat new-intstances (if
                                                         (not-empty pt-instance-params)
                                                         filter-instances
                                                         instances)))

        group-xml-tests (group-by
                          (fn [test]
                            [(eval/sf-test-suite->pt-test-id options test)
                             (when pt-instance-params (get-parameters-map test pt-instance-params))])
                          org-xml-tests)

        instance-to-ts-test (group-by (fn [inst] [(:set-id (:attributes inst)) (:test-id (:attributes inst))]) all-intstances)]
    (when display-run-time (print-run-time "Time - after create instances: %d:%d:%d" start-time))
    [test-by-id instance-to-ts-test testid-to-params group-xml-tests]))

(defn make-runs [[test-by-id instance-to-ts-test test-name-to-params group-xml-tests] client {:keys [display-action-logs] :as options} start-time]
  (when display-action-logs (log/infof "make-runs"))
  (flatten (doall
             (for [[test-testset instances] instance-to-ts-test]
               (map
                 (fn [instance]
                   (let [[_ test-id] test-testset
                         tst (first (get test-by-id test-id))
                         test-name (get tst 0)
                         this-param (:parameters (:attributes instance))
                         ;; Use nil in case of empty params
                         this-param (if (empty? this-param)
                                      nil
                                      this-param)
                         xml-test (get group-xml-tests [test-name this-param])
                         sys-test (get tst 1)
                         [run run-steps] (eval/sf-test-suite->run-def options (first xml-test) sys-test this-param)
                         additional-run-fields (eval/eval-additional-fields run (:additional-run-fields options))
                         additional-run-fields (merge additional-run-fields (:system-fields additional-run-fields))
                         run (eval/sf-test-run->run-def additional-run-fields sys-test)]
                     {:instance-id (:id instance)
                      :attributes  run
                      :steps       run-steps})) instances)))))

(defn create-runs [runs client options start-time]
  (doall (for [runs-part (partition-all 20 (shuffle runs))]
           (api/ll-create-runs client [(:project-id options) (:display-action-logs options)] runs-part))))
