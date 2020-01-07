package network;

import network.aika.Document;
import network.aika.Model;
import network.aika.neuron.Neuron;
import network.aika.neuron.activation.Activation;
import network.aika.neuron.excitatory.ExcitatorySynapse;
import network.aika.neuron.excitatory.NegExcitatorySynapse;
import network.aika.neuron.excitatory.PatternNeuron;
import network.aika.neuron.inhibitory.InhibitoryNeuron;
import network.aika.neuron.inhibitory.InhibitorySynapse;
import org.junit.Test;

public class MutualExclusionTest {


    @Test
    public void testPropagation() {
        Model m = new Model();

        PatternNeuron in = new PatternNeuron(m, "IN");
        PatternNeuron na = new PatternNeuron(m, "A");
        PatternNeuron nb = new PatternNeuron(m, "B");
        PatternNeuron nc = new PatternNeuron(m, "C");
        InhibitoryNeuron inhib = new InhibitoryNeuron(m, "I");

        Neuron.init(na.getProvider(), 1.0,
                new ExcitatorySynapse.Builder()
                        .setNeuron(in)
                        .setWeight(10.0)
                        .setRecurrent(false),
                new NegExcitatorySynapse.Builder()
                        .setNeuron(inhib)
                        .setWeight(-100.0)
                        .setRecurrent(true)
        );

        Neuron.init(nb.getProvider(), 1.5,
                new ExcitatorySynapse.Builder()
                        .setNeuron(in)
                        .setWeight(10.0)
                        .setRecurrent(false),
                new NegExcitatorySynapse.Builder()
                        .setNeuron(inhib)
                        .setWeight(-100.0)
                        .setRecurrent(true)
        );

        Neuron.init(nc.getProvider(), 1.2,
                new ExcitatorySynapse.Builder()
                        .setNeuron(in)
                        .setWeight(10.0)
                        .setRecurrent(false),
                new NegExcitatorySynapse.Builder()
                        .setNeuron(inhib)
                        .setWeight(-100.0)
                        .setRecurrent(true)
        );

        Neuron.init(inhib.getProvider(), 0.0,
                new InhibitorySynapse.Builder()
                        .setNeuron(na)
                        .setWeight(1.0),
                new InhibitorySynapse.Builder()
                        .setNeuron(nb)
                        .setWeight(1.0),
                new InhibitorySynapse.Builder()
                        .setNeuron(nc)
                        .setWeight(1.0)
                );




        Document doc = new Document(m, "test");

        in.addInput(doc,
                new Activation.Builder()
                    .setValue(1.0)
                    .setInputTimestamp(0)
                    .setFired(0)
        );

        System.out.println(doc.activationsToString());
    }
}
