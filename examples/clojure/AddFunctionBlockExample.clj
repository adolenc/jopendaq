;; Load a function block from the bundled modules (a statistics block), wire a
;; channel signal into its input port, and read the averaged output.

(import '(com.opendaq InstanceBuilder Instance Daq Channel FunctionBlock MultiReader))

;; Building the instance explicitly, to show where the modules come from; plain
;; (Instance.) does exactly this.  The builder's configuration methods return
;; the builder, so they thread.
(def instance (-> (InstanceBuilder.)
                  (.setModulePath (.toString (Daq/nativeLibraryDirectory)))  ; the bundled modules
                  ;; (.addModulePath "/path/to/your/modules")                 ; your own modules folder
                  (.build)))
(.addDevice instance "daqref://device0")

(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(.setPropertyValue channel "Amplitude" 5.0)
(.setPropertyValue channel "DC" 1.0)

(def statistics (.addFunctionBlock instance "RefFBModuleStatistics"))
(.setPropertyValue statistics "BlockSize" 100)

(let [statuses (.getStatusContainer statistics)]
  (println (str "Before connect: " (.getValue (.getStatus statuses "ComponentStatus"))
                " (" (.getStatusMessage statuses "ComponentStatus") ")")))

(def port (.get (.getInputPorts statistics) 0))
(.connect port (.get (.getSignals channel) 0))

(let [statuses (.getStatusContainer statistics)]
  (println (str "After connect:  " (.getValue (.getStatus statuses "ComponentStatus"))
                " (" (.getStatusMessage statuses "ComponentStatus") ")")))

;; Match on local-id, not name: a signal's name is a mutable display label,
;; while its local-id is the stable identifier within its parent.
(def signals (.getSignals statistics))
(def avg (first (filter #(= (.getLocalId %) "avg") signals)))
(def rms (first (filter #(= (.getLocalId %) "rms") signals)))
(def reader (MultiReader. [avg rms]))

;; The first reads may return nothing while the block waits for a complete
;; input descriptor, so retry until samples arrive.
(when-not
  (loop [attempt 0]
    (if (>= attempt 20)
      false
      (let [values (.read reader 5 1000)]
        (if (pos? (alength (.get values 0)))
          (do
            (printf "%10s%12s%n" "avg" "rms")
            (dotimes [i (alength (.get values 0))]
              (printf "%10.4f%12.4f%n" (aget (.get values 0) i) (aget (.get values 1) i)))
            (flush)
            true)
          (recur (inc attempt))))))
  (println "Statistics block produced no samples in time."))
