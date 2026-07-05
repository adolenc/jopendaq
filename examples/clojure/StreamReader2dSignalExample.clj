;; Read a dimensioned ("2-D") signal: an FFT function block turns the channel
;; stream into spectra, where each sample is a whole vector of amplitude bins.
;; readMatrix returns a (samples x bins) matrix, and the frequency axis comes
;; off the value descriptor's single dimension.

(import '(com.opendaq Instance Channel FunctionBlock StreamReader))

(def instance (Instance.))
(.addDevice instance "daqref://device0")

(def channel (.asType (.findComponent instance "Dev/RefDev0/IO/AI/RefCh0") Channel))
(.setPropertyValue channel "Waveform" 0)          ; 0 = Sine
(.setPropertyValue channel "Frequency" 125.0)
(.setPropertyValue channel "Amplitude" 5.0)
(.setPropertyValue channel "NoiseAmplitude" 0.1)

(def fft (.addFunctionBlock instance "RefFBModuleFFT"))
(.setPropertyValue fft "BlockSize" 16)
(.connect (.get (.getInputPorts fft) 0) (.get (.getSignals channel) 0))
(def signal (.get (.getSignals fft) 0))

;; Wait for the block to publish its output descriptor, then read the frequency
;; axis off the value descriptor's single dimension.
(Thread/sleep 1000)
(def dimension (.get (.getDimensions (.getDescriptor signal)) 0))
(def axis (.getLabels dimension))

;; Read 5 samples.  Each sample is a full spectrum, so readMatrix returns a
;; (samples x bins) matrix; retry until 5 rows have arrived (the first reads may
;; come back short while the stream warms up).
(def reader (StreamReader. signal))
(def spectra
  (loop [attempt 0]
    (let [s (.readMatrix reader 5 1000)]
      (if (or (= (alength s) 5) (>= attempt 49))
        s
        (recur (inc attempt))))))

;; Print the axis down the rows and one column of amplitudes per sample.  The
;; 125 Hz tone dominates a single bin (~5, our amplitude) while noise fills the
;; rest with small values.  The reference block labels its bins one step
;; (31.25 Hz) below the true bin centre, so the tone lands in the 93.75 Hz row.
(println (str (.getName dimension) " spectrum, " (.size axis) " bins, "
              (.getSymbol (.getUnit dimension))))
(println)
(printf "%12s" "freq (Hz)")
(dotimes [i (alength spectra)]
  (printf "%14s" (str "sample " (inc i))))
(println)
(dotimes [bin (.size axis)]
  (printf "%12.2f" (.doubleValue ^Number (.get axis bin)))
  (doseq [spectrum spectra]
    (printf "%14.4f" (aget spectrum bin)))
  (println))
(flush)
