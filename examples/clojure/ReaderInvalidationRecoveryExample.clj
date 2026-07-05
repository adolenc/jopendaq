;; A reader is invalidated when its signal's descriptor changes to a sample
;; type the reader cannot convert to its configured read type.  The reader
;; needs to be recovered by creating a new one via the from-existing factory.

(import '(com.opendaq Instance Channel StreamReader SampleType ReaderInvalidatedException))

(defn show-averages
  "Read `count` averages with their domain values and print them (retrying
   while the stream warms up); the domain array is float64 or int64 depending
   on the reader's domain read type."
  [reader count]
  (loop [attempt 0]
    (when (>= attempt 50)
      (throw (IllegalStateException. "The statistics stream produced no averages.")))
    (let [result (.readWithDomain reader count 1000)
          averages (.doubleValues result)]
      (if (pos? (alength averages))
        (let [domain (.domain result)
              long-domain? (= (class domain) (class (long-array 0)))]
          (dotimes [i (alength averages)]
            (let [tick (if long-domain?
                         (aget ^longs domain i)
                         (long (aget ^doubles domain i)))]
              (printf "  tick %d  ->  avg %.3f%n" tick (aget averages i))))
          (flush))
        (recur (inc attempt))))))

(def instance (Instance.))
(.addDevice instance "daqref://device0")
(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(.setPropertyValue channel "Waveform" 0)
(.setPropertyValue channel "Amplitude" 0.0)
(.setPropertyValue channel "DC" 2.0)
(.setPropertyValue channel "NoiseAmplitude" 0.0)

;; Average the channel in blocks of 10 samples.  DomainSignalType starts at
;; 0 = Implicit: the output domain is int64 ticks under a linear rule.
(def statistics (.addFunctionBlock instance "RefFBModuleStatistics"))
(.connect (.get (.getInputPorts statistics) 0) (.get (.getSignals channel) 0))
(def avg-signal (.get (.getSignals statistics) 0))

;; Phase 1: read averages with the domain ticks as doubles -- fine while the
;; domain is int64, which converts to float64.
(def reader (StreamReader. avg-signal SampleType/FLOAT64 SampleType/FLOAT64))
(println "implicit int64 domain, read as float64:")
(show-averages reader 4)

;; Phase 2: switch the output domain to 2 = ExplicitRange -- each average's
;; domain value becomes the RangeInt64 tick range of the block it covers, and
;; RangeInt64 has no conversion to float64.  The change reaches the reader as a
;; descriptor-changed event in its packet queue; read hands over the samples
;; queued before the change, then hits the event and throws.
(.setPropertyValue statistics "DomainSignalType" 2)
(def recovered
  (try
    (dotimes [_ 50]
      (.readWithDomain reader 100 200))
    (throw (IllegalStateException. "Expected the domain change to invalidate the reader."))
    (catch ReaderInvalidatedException e
      (println (.getMessage e))
      (StreamReader/createStreamReaderFromExisting (.reader e) SampleType/FLOAT64 SampleType/INT64))))

(println "explicit RangeInt64 domain, read as int64 range starts:")
(show-averages recovered 4)
