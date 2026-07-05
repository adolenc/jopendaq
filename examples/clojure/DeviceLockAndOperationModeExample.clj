;; Switch a device between operation modes and lock/unlock it against
;; configuration changes.

(import '(com.opendaq Instance Device OperationModeType))
(require '[clojure.string :as str])

(defn label [mode]
  (->> (str/split (str/lower-case (.name mode)) #"_")
       (map str/capitalize)
       (str/join "")))

(def instance (Instance.))
(def device (.addDevice instance "daqref://device0"))

(def modes (.getAvailableOperationModes device))
(println (str "Available operation modes: "
              (str/join ", " (map #(label (OperationModeType/fromValue (.intValue %))) modes))))
(println (str "Current operation mode:    " (label (.getOperationMode device))))

(.setOperationMode device OperationModeType/OPERATION)
(.lock device)
(println (str "After setting Operation:   " (label (.getOperationMode device))))
(println (str "Device locked: " (.isLocked device)))

(.unlock device)
(.setOperationMode device OperationModeType/SAFE_OPERATION)
(println)
(println (str "Device locked: " (.isLocked device)))
(println (str "Final operation mode:      " (label (.getOperationMode device))))
