(ns practitest-firecracker.eval
  (:require
    [clojure.string :as string]
    [practitest-firecracker.query-dsl :refer [query? eval-query read-query]]
    [clojure.walk :refer [postwalk]]
    [practitest-firecracker.utils :refer [pformat replace-map replace-keys]]
    [practitest-firecracker.api :as api]
    [clojure.pprint :as pprint]))

(def custom-field-cache (atom {}))

(defn eval-additional-fields [suite additional-fields]
  (postwalk #(if (query? %) (eval-query suite %) %)
            additional-fields))

(defn sf-test-suite->pt-test-name [options suite]
  ;; Special case for automatically detected BDD scenarios - will use outline ID instead of generated one
  (if-let [scenario (:gherkin-scenario suite)]
    (:id scenario) ;; Usar el ID del escenario BDD
    (let [test-id (:id suite)] ;; Obtener el ID del test suite
      (if (string/blank? test-id) "UNNAMED" test-id)))) ;; Devolver "UNNAMED" si el ID está en blanco

(defn sf-test-suite->pt-test-id [options suite]
  ;; Special case for automatically detected BDD scenarios - will use outline ID instead of generated one
  (if-let [scenario (:gherkin-scenario suite)]
    (:id scenario) ;; Use the ID of the BDD scenario
    (let [test-id (:id suite)] ;; Get the ID of the test suite
      (if (string/blank? test-id) "UNNAMED" test-id)))) ;; Return "UNNAMED" if the ID is blank

(defn sf-test-case->pt-step-description [options test-case]
  (let [step-name (eval-query test-case (:pt-test-step-description options))]
    (if (string/blank? step-name) "UNNAMED" step-name)))

(defn sf-test-case->pt-step-name [options test-case]
  (let [step-name (eval-query test-case (:pt-test-step-name options))]
    (if (string/blank? step-name) "UNNAMED" step-name)))

(defn sf-test-case->step-def [options test-case]
  {:name (sf-test-case->pt-step-name options test-case)
   :description (sf-test-case->pt-step-description options test-case)})

(defn sf-test-suite->test-def [options test-suite]
  (if (:bdd-test? test-suite)
    ;; Special logic for BDD tests
    [{:name (sf-test-suite->pt-test-name options test-suite)
      :test-type "BDDTest"
      ;; Attach scenario source
      :scenario (:scenario-source (:gherkin-scenario test-suite))}
     ;; No steps for BDD
     []]
    ;; Usual logic
    [{:id (Integer/parseInt (:id test-suite))}
     (map (partial sf-test-case->step-def options) (:test-cases test-suite))]))

(defn group-test-names [tests options]
  (doall (for [test tests]
           {:name_exact (sf-test-suite->pt-test-name options test)})))

(defn group-test-ids [tests options]
  (doall (for [test tests]
           {:id (Integer/parseInt (:id test))})))

(defn ensure-custom-field-values [client [project-id display-action-logs] custom-fields]
  (doseq [[cf v] custom-fields
          :let [cf-id (some-> (last (re-find #"^---f-(\d+)$" (name cf)))
                              (Long/parseLong))]
          :when (not (nil? cf-id))]
    (when-not (contains? @custom-field-cache cf-id)
      (let [cf (api/ll-get-custom-field client [project-id display-action-logs] cf-id)]
        (swap! custom-field-cache assoc cf-id [(get-in cf [:attributes :field-format])
                                               (set (get-in cf [:attributes :possible-values]))])))
    (let [[field-format possible-values] (get @custom-field-cache cf-id)]
      (when (= "list" field-format)
        (when-not (contains? possible-values v)
          (swap! custom-field-cache update-in [cf-id 1] conj v)
          (api/ll-update-custom-field client [project-id display-action-logs] cf-id (vec (conj possible-values v))))))))

(defn create-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        test (api/ll-find-test client [project-id display-action-logs] (:id test-def))
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (if test
      test
      (api/ll-create-test client
                      project-id
                      (merge test-def
                             {:author-id (:author-id options)}
                             additional-test-fields)
                      step-defs))))

(defn update-sf-test [client {:keys [project-id display-action-logs] :as options} sf-test-suite test]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        additional-test-fields (eval-additional-fields sf-test-suite (:additional-test-fields options))
        additional-test-fields (merge additional-test-fields (:system-fields additional-test-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-test-fields))
    (api/ll-update-test client
                    [project-id display-action-logs]
                    (merge test-def
                           {:author-id (:author-id options)
                            :test-type (:test-type (:attributes test))}
                           additional-test-fields)
                    step-defs
                    (read-string (:id test)))))

(defn update-sf-testset [client {:keys [project-id display-action-logs] :as options} testset-name sf-test-suite testset-id]
  (let [[test-def step-defs] (sf-test-suite->test-def options sf-test-suite)
        additional-testset-fields (:additional-testset-fields options)
        additional-testset-fields (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (ensure-custom-field-values client [project-id display-action-logs] (:custom-fields additional-testset-fields))
    (api/ll-update-testset client
                       [project-id display-action-logs]
                       (merge test-def
                              {:name testset-name}
                              {:author-id (:author-id options)}
                              additional-testset-fields)
                       step-defs
                       testset-id)))

(defn create-sf-testset [client options sf-test-suites testset-name]
  (let [tests (map (partial create-sf-test client options) sf-test-suites)
        additional-testset-fields (:additional-testset-fields options)
        additional-testset-fields (merge additional-testset-fields (:system-fields additional-testset-fields))]
    (api/ll-create-testset client
                       (:project-id options)
                       (merge {:name testset-name}
                              additional-testset-fields)
                       (map :id tests))))

(defn is-failed-step [only-failed-steps desc failure-detail]
  (or (not only-failed-steps)
    (and
      (not (nil? failure-detail))
      (not (nil? desc))
      (string/includes? failure-detail desc))))

(defn sf-test-case->run-step-def [options params test-suite-cases test-case]
  (let [grouped-case  (group-by (fn [case] (sf-test-case->pt-step-description options case)) test-suite-cases)
        description (or (:description test-case) (sf-test-case->pt-step-description options test-case))
        new-desc    (replace-map description (replace-keys params))
        origin-case (first (get grouped-case new-desc))
        msg         (str (or (:failure-message origin-case) (:failure-message test-case)) \newline (:failure-detail test-case))]
    {:name           (if (nil? (:position test-case))
                       (sf-test-case->pt-step-name options test-case)
                       (or (:pt-test-step-name test-case)
                           (sf-test-case->pt-step-name options test-case)))
     :description    new-desc
     :actual-results (when (is-failed-step (:only-failed-steps options) new-desc msg) msg)
     :status         (case (when (is-failed-step (:only-failed-steps options) new-desc (:failure-detail test-case)) (:failure-type test-case))
                       :failure "FAILED"
                       :skipped "N/A"
                       :error "FAILED"
                       ;; will leave error as FAILED for now, will change it after we add the UI changes and add the option of ERROR to Reqirement Test and TestSet table of runs
                       nil "PASSED"
                       "NO RUN")}))

(defn bdd-test-case->run-step-def [test-case]
  {:name (:pt-test-step-name test-case)
   :description (:description test-case)
   ;; Include actual results for failure and error cases
   :actual-results (when (#{:failure :error} (:status test-case))
                     (:failure-detail test-case))
   :status (case (:status test-case)
             :failure "FAILED"
             :skipped "N/A"
             :error "FAILED"
             nil "PASSED"
             "NO RUN")})

(defn sf-test-suite->run-def [options test-suite sys-test params]
  [{:run-duration (:time-elapsed sys-test)}
   (if (:detect-bdd-steps options)
     (map bdd-test-case->run-step-def (:test-cases test-suite))
     (map (partial sf-test-case->run-step-def options params (:test-cases test-suite))
          (sort-by :position (:test-cases sys-test))))])

(defn sf-test-run->run-def [custom-fields run-duration]
  {:run-duration  (:time-elapsed run-duration),
   :custom-fields (:custom-fields custom-fields)})