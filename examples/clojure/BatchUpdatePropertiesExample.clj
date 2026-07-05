;; Batch several property writes into one update per object: values set inside
;; the batch are staged rather than applied -- reads still return the old
;; values and no property events fire until the batch closes -- and committed
;; atomically per object when the with-open scope ends.

(import '(com.opendaq Instance Channel))

(defn show-settings [label channel]
  (println (str label (.getName channel)
                ":  Amplitude=" (.getPropertyValue channel "Amplitude")
                "  Frequency=" (.getPropertyValue channel "Frequency")
                "  Waveform=" (.getPropertyValue channel "Waveform"))))

(def instance (Instance.))
(def device (.addDevice instance "daqref://device0"))
(def ch0 (.get (.getChannels device) 0))
(def ch1 (.get (.getChannels device) 1))

(show-settings "Before:          " ch0)
(show-settings "Before:          " ch1)

;; batchUpdates returns an AutoCloseable; with-open commits it (per object) as
;; the scope ends, staging every write made inside.
(with-open [batch0 (.batchUpdates ch0)
            batch1 (.batchUpdates ch1)]
  (.setPropertyValue ch0 "Amplitude" 2.5)
  (.setPropertyValue ch0 "Frequency" 25.0)
  (.setPropertyValue ch0 "Waveform" 1)
  (.setPropertyValue ch1 "Amplitude" 4.0)
  (.setPropertyValue ch1 "Frequency" 50.0)
  (.setPropertyValue ch1 "Waveform" 2)
  ;; Still inside the batch: the writes above are staged but not applied.
  (show-settings "During (staged): " ch0)
  (show-settings "During (staged): " ch1))

(show-settings "After the batch: " ch0)
(show-settings "After the batch: " ch1)
