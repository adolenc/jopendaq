import java.util.List;

import org.opendaq.*;

/**
 * Read a dimensioned ("2-D") signal: an FFT function block turns the channel
 * stream into spectra, where each sample is a whole vector of amplitude bins.
 * readMatrix returns a (samples x bins) matrix, and the frequency axis comes
 * off the value descriptor's single dimension.
 */
public class StreamReader2dSignalExample {

    public static void main(String[] args) throws Exception {
        Instance instance = new Instance();
        instance.addDevice("daqref://device0");

        Channel channel = instance.findComponent("Dev/RefDev0/IO/AI/RefCh0").asType(Channel.class);
        channel.setPropertyValue("Waveform", 0);          // 0 = Sine
        channel.setPropertyValue("Frequency", 125.0);
        channel.setPropertyValue("Amplitude", 5.0);
        channel.setPropertyValue("NoiseAmplitude", 0.1);

        FunctionBlock fft = instance.addFunctionBlock("RefFBModuleFFT");
        fft.setPropertyValue("BlockSize", 16);
        fft.getInputPorts().get(0).connect(channel.getSignals().get(0));
        Signal signal = fft.getSignals().get(0);

        // Wait for the block to publish its output descriptor, then read the
        // frequency axis off the value descriptor's single dimension.
        Thread.sleep(1000);
        Dimension dimension = signal.getDescriptor().getDimensions().get(0);
        List<Object> axis = dimension.getLabels();

        // Read 5 samples.  Each sample is a full spectrum, so readMatrix
        // returns a (samples x bins) matrix; retry until 5 rows have arrived
        // (the first reads may come back short while the stream warms up).
        StreamReader reader = new StreamReader(signal);
        double[][] spectra = new double[0][];
        for (int attempt = 0; attempt < 50; attempt++) {
            spectra = reader.readMatrix(5, 1000);
            if (spectra.length == 5) {
                break;
            }
        }

        // Print the axis down the rows and one column of amplitudes per
        // sample.  The 125 Hz tone dominates a single bin (~5, our amplitude)
        // while noise fills the rest with small values.  The reference block
        // labels its bins one step (31.25 Hz) below the true bin centre, so
        // the tone lands in the 93.75 Hz row.
        System.out.println(dimension.getName() + " spectrum, " + axis.size() + " bins, "
            + dimension.getUnit().getSymbol());
        System.out.println();
        System.out.printf("%12s", "freq (Hz)");
        for (int i = 0; i < spectra.length; i++) {
            System.out.printf("%14s", "sample " + (i + 1));
        }
        System.out.println();
        for (int bin = 0; bin < axis.size(); bin++) {
            System.out.printf("%12.2f", ((Number) axis.get(bin)).doubleValue());
            for (double[] spectrum : spectra) {
                System.out.printf("%14.4f", spectrum[bin]);
            }
            System.out.println();
        }
    }
}
