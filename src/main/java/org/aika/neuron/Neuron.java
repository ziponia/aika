/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aika.neuron;


import org.aika.*;
import org.aika.Activation.State;
import org.aika.Activation.SynapseActivation;
import org.aika.corpus.Document;
import org.aika.corpus.SearchNode;
import org.aika.corpus.InterprNode;
import org.aika.corpus.Range;
import org.aika.corpus.Range.Operator;
import org.aika.lattice.InputNode;
import org.aika.lattice.InputNode.SynapseKey;
import org.aika.lattice.Node;
import org.aika.lattice.Node.ThreadState;
import org.aika.lattice.OrNode;
import org.aika.neuron.Synapse.Key;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The <code>Neuron</code> class represents a neuron in Aikas neural network and is connected to other neurons through
 * input synapses and output synapses. The activation value of a neuron is calculated by computing the weighted sum
 * (input act. value * synapse weight) of the input synapses, adding the bias to it and sending the resulting value
 * through a transfer function (the upper part of tanh).
 *
 * The neuron does not store its activations by itself. The activation objects are stored within the
 * logic nodes. To access the activations of this neuron simply use the member variable <code>node</code>.
 *
 * @author Lukas Molzberger
 */
public class Neuron implements Comparable<Neuron>, Writable {

    private static final Logger log = LoggerFactory.getLogger(Neuron.class);

    public final static double LEARN_RATE = 0.01;
    public static final double WEIGHT_TOLERANCE= 0.001;
    public static final double TOLERANCE = 0.000001;
    public static final int MAX_SELF_REFERENCING_DEPTH = 5;

    public Model m;

    public static AtomicInteger currentNeuronId = new AtomicInteger(0);
    public int id = currentNeuronId.addAndGet(1);
    public String label;

    public volatile double bias;
    public volatile double negDirSum;
    public volatile double negRecSum;
    public volatile double posRecSum;

    public volatile double maxRecurrentSum = 0.0;


    public TreeSet<Synapse> outputSynapses = new TreeSet<>(Synapse.OUTPUT_SYNAPSE_COMP);
    public TreeSet<Synapse> inputSynapses = new TreeSet<>(Synapse.INPUT_SYNAPSE_COMP);
    public TreeSet<Synapse> inputSynapsesByWeight = new TreeSet<>(Synapse.INPUT_SYNAPSE_BY_WEIGHTS_COMP);

    public TreeMap<Key, InputNode> outputNodes = new TreeMap<>();

    public Node node;
    public boolean initialized = false;

    public boolean isBlocked;
    public boolean noTraining;

    public volatile double activationSum;
    public volatile int numberOfActivations;

    public ReadWriteLock lock = new ReadWriteLock();


    public Neuron() {
    }


    public Neuron(String label) {
        this.label = label;
    }


    public Neuron(String label, boolean isBlocked, boolean noTraining) {
        this.label = label;
        this.isBlocked = isBlocked;
        this.noTraining = noTraining;
    }


    public double avgActivation() {
        return numberOfActivations > 0.0 ? activationSum / numberOfActivations : 1.0;
    }


    public static Neuron create(Document doc, Neuron n, double bias, double negDirSum, double negRecSum, double posRecSum, Set<Synapse> inputs) {
        n.m = doc.m;
        n.m.stat.neurons++;
        n.bias = bias;
        n.negDirSum = negDirSum;
        n.negRecSum = negRecSum;
        n.posRecSum = posRecSum;

        n.lock.acquireWriteLock(doc.threadId);
        n.node = new OrNode(doc);
        n.node.neuron = n;
        n.lock.releaseWriteLock();

        double sum = 0.0;
        for(Synapse s: inputs) {
            assert !s.key.startRangeOutput || s.key.startRangeMatch == Operator.EQUALS || s.key.startRangeMatch == Operator.FIRST;
            assert !s.key.endRangeOutput || s.key.endRangeMatch == Operator.EQUALS || s.key.endRangeMatch == Operator.FIRST;

            s.output = n;
            s.link(doc);

            if(s.maxLowerWeightsSum == Double.MAX_VALUE) {
                s.maxLowerWeightsSum = sum;
            }

            sum += s.w;
        }

        if(!Node.adjust(doc, n, -1)) return null;

        n.publish(doc);

        n.initialized = true;
        return n;
    }


    public void publish(Document doc) {
        m.neurons.put(id, this);
    }


    public void unpublish(Document doc) {
        m.neurons.remove(this);
    }


    public void remove(Document doc) {
        unpublish(doc);

        for(Synapse s: inputSynapses) {
            s.input.lock.acquireWriteLock(doc.threadId);
            s.input.outputSynapses.remove(s);
            s.input.lock.releaseWriteLock();
        }

        for(Synapse s: outputSynapses) {
            s.output.lock.acquireWriteLock(doc.threadId);
            s.output.inputSynapses.remove(s);
            s.output.inputSynapsesByWeight.remove(s);
            s.output.lock.releaseWriteLock();
        }
    }



    public void propagateAddedActivation(Document doc, Activation act) {
        doc.ubQueue.add(act);
    }


    public void propagateRemovedActivation(Document doc, Activation act) {
        for(InputNode out: outputNodes.values()) {
            out.removeActivation(doc, act);
        }
    }


    public void computeBounds(Activation act) {
        double ub = bias + posRecSum - (negDirSum + negRecSum);
        double lb = bias + posRecSum - (negDirSum + negRecSum);

        for (SynapseActivation sa: act.neuronInputs) {
            Synapse s = sa.s;
            Activation iAct = sa.input;

            if(iAct == act || iAct.isRemoved) continue;

            if (s.key.isNeg) {
                if (!checkSelfReferencing(act.key.o, iAct.key.o, null, 0) && act.key.o.contains(iAct.key.o, true)) {
                    ub += iAct.lowerBound * s.w;
                }

                lb += s.w;
            } else {
                ub += iAct.upperBound * s.w;
                lb += iAct.lowerBound * s.w;
            }
        }

        act.upperBound = transferFunction(ub);
        act.lowerBound = transferFunction(lb);
    }


    public State computeWeight(int round, Activation act, SearchNode en, Document doc) {
        double directSum = bias - (negDirSum + negRecSum);
        double recurrentSum = 0.0;

        int fired = -1;

        if(round == 0) {
            recurrentSum += posRecSum;
        }

        ArrayList<SynapseActivation> tmp = new ArrayList<>();
        Synapse lastSynapse = null;
        SynapseActivation maxSA = null;
        for (SynapseActivation sa: act.neuronInputs) {
            if(lastSynapse != null && lastSynapse != sa.s) {
                tmp.add(maxSA);
                maxSA = null;
            }
            if(maxSA == null || maxSA.input.rounds.get(sa.s.key.isRecurrent ? round - 1 : round).value < sa.input.rounds.get(sa.s.key.isRecurrent ? round - 1 : round).value) {
                maxSA = sa;
            }
            lastSynapse = sa.s;
        }
        if(maxSA != null) {
            tmp.add(maxSA);
        }

        for (SynapseActivation sa: tmp) {
            Synapse s = sa.s;

            Activation iAct = sa.input;

            if (iAct == act || iAct.isRemoved) continue;

            if (s.key.isNeg && s.key.isRecurrent) {
                if (!checkSelfReferencing(act.key.o, iAct.key.o, en, 0)) {
                    if (round == 0) {
                        if (en.isCovered(iAct.key.o.markedCovered)) {
                            recurrentSum += s.w;
                        }
                    } else {
                        State is = iAct.rounds.get(round - 1);

                        recurrentSum += is.value * s.w;
                    }
                }
            } else if (s.key.isNeg && !s.key.isRecurrent) {
                State is = iAct.rounds.get(round);
                directSum += is.value * s.w;

            } else if (!s.key.isNeg && s.key.isRecurrent) {
                State is = iAct.rounds.get(round - 1);
                recurrentSum += is.value * s.w;

            } else if (!s.key.isNeg && !s.key.isRecurrent) {
                State is = iAct.rounds.get(round);
                directSum += is.value * s.w;

                if (directSum + recurrentSum >= 0.0 && fired < 0) {
                    fired = iAct.rounds.get(round).fired + 1;
                }
            }
        }

        boolean covered = en.isCovered(act.key.o.markedCovered);

        double sum = directSum + recurrentSum;

        // Compute only the recurrent part is above the threshold.
        NormWeight newWeight = NormWeight.create(
                covered ? (directSum + negRecSum) < 0.0 ? Math.max(0.0, sum) : recurrentSum - negRecSum : 0.0,
                (directSum + negRecSum) < 0.0 ? Math.max(0.0, directSum + negRecSum + maxRecurrentSum) : maxRecurrentSum
        );

        if(doc.debugActId == act.id && doc.debugActWeight <= newWeight.w) {
            storeDebugOutput(doc, tmp, newWeight, sum, round);
        }

        return new State(
                covered ? transferFunction(sum) : 0.0,
                covered ? fired : -1,
                newWeight
        );
    }


    private void storeDebugOutput(Document doc, List<SynapseActivation> inputs, NormWeight nw, double sum, int round) {
        StringBuilder sb = new StringBuilder();
        sb.append("Activation ID: " + doc.debugActId + "\n");
        sb.append("Neuron: " + label + "\n");
        sb.append("Sum: " + sum + "\n");
        sb.append("Bias: " + bias + "\n");
        sb.append("Round: " + round + "\n");
        sb.append("Positive Recurrent Sum: " + posRecSum + "\n");
        sb.append("Negative Recurrent Sum: " + negRecSum + "\n");
        sb.append("Negative Direct Sum: " + negDirSum + "\n");
        sb.append("Inputs:\n");

        for(SynapseActivation sa: inputs) {
            String actValue = "";
            if(sa.s.key.isRecurrent) {
                if(round > 0) {
                    actValue = "" + sa.input.rounds.get(round - 1);
                }
            } else {
                actValue = "" + sa.input.rounds.get(round);
            }

            sb.append("    " + sa.input.key.n.neuron.label + "  SynWeight: " + sa.s.w + "  ActValue: " + actValue);
            sb.append("\n");
        }
        sb.append("Weight: " + nw.w + "\n");
        sb.append("Norm: " + nw.n +"\n");
        sb.append("\n");
        doc.debugOutput = sb.toString();
    }


    public void computeErrorSignal(Document doc, Activation act) {
        act.errorSignal = act.initialErrorSignal;
        for(SynapseActivation sa: act.neuronOutputs) {
            Synapse s = sa.s;
            Activation oAct = sa.output;

            act.errorSignal += s.w * oAct.errorSignal * (1.0 - act.finalState.value);
        }

        for(SynapseActivation sa: act.neuronInputs) {
            doc.bQueue.add(sa.input);
        }
    }


    public void train(Document doc, Activation act) {
        if(Math.abs(act.errorSignal) < TOLERANCE) return;

        long v = Activation.visitedCounter++;
        Range targetRange = null;
        if(act.key.r != null) {
            int s = act.key.r.end - act.key.r.begin;
            targetRange = new Range(Math.max(0, act.key.r.begin - (s / 2)), Math.min(doc.length(), act.key.r.end + (s / 2)));
        }
        ArrayList<Activation> inputActs = new ArrayList<>();
        for(Activation iAct: doc.inputNodeActivations) {
            if(Range.overlaps(iAct.key.r, targetRange)) {
                inputActs.add(iAct);
            }
        }

        if(Document.TRAIN_DEBUG_OUTPUT) {
            log.info("Debug discover:");

            log.info("Old Synapses:");
            for(Synapse s: inputSynapsesByWeight) {
                log.info("S:" + s.input + " RID:" + s.key.relativeRid + " W:" + s.w);
            }
            log.info("");
        }

        for(SynapseActivation sa: act.neuronInputs) {
            inputActs.add(sa.input);
        }

        for(Activation iAct: inputActs) {
            Integer rid = Utils.nullSafeSub(iAct.key.rid, false, act.key.rid, false);
            train(doc, iAct, rid, LEARN_RATE * act.errorSignal, v);
        }

        if(Document.TRAIN_DEBUG_OUTPUT) {
            log.info("");
        }

        Node.adjust(doc, this, act.errorSignal > 0.0 ? 1 : -1);
    }


    public void train(Document doc, Activation iAct, Integer rid, double x, long v) {
        if(iAct.visitedNeuronTrain == v) return;
        iAct.visitedNeuronTrain = v;

        Activation iiAct = iAct.inputs.firstEntry().getValue();
        if(iiAct.key.n.neuron != this && iiAct.finalState != null && iiAct.finalState.value > TOLERANCE) {
            InputNode in = (InputNode) iAct.key.n;

            double deltaW = x * iiAct.finalState.value;

            SynapseKey sk = new SynapseKey(rid, this);
            Synapse s = in.getSynapse(sk);
            if(s == null) {
                s = new Synapse(
                        iiAct.key.n.neuron,
                        new Key(
                                in.key.isNeg,
                                in.key.isRecurrent,
                                rid,
                                null,
                                in.key.startRangeMatch,
                                in.key.startRangeMapping,
                                in.key.startRangeOutput,
                                in.key.endRangeMatch,
                                in.key.endRangeMapping,
                                in.key.endRangeOutput
                        )
                );
                s.output = this;
                in.setSynapse(doc, sk, s);
                s.link(doc);
            }

            inputSynapses.remove(s);
            inputSynapsesByWeight.remove(s);

            double oldW = s.w;
            s.w -= deltaW;

            if(Document.TRAIN_DEBUG_OUTPUT) {
                log.info("S:" + s.input + " RID:" + s.key.relativeRid + " OldW:" + oldW + " NewW:" + s.w);
            }
            inputSynapses.add(s);
            inputSynapsesByWeight.add(s);
        }
    }


    private static boolean checkSelfReferencing(InterprNode nx, InterprNode ny, SearchNode en, int depth) {
        if(nx == ny && (en == null || en.isCovered(ny.markedCovered))) return true;

        if(depth > MAX_SELF_REFERENCING_DEPTH) return false;

        if(ny.orInterprNodes != null) {
            for (InterprNode n: ny.orInterprNodes.values()) {
                if(checkSelfReferencing(nx, n, en, depth + 1)) return true;
            }
        }

        return false;
    }


    public static double transferFunction(double x) {
        return x > 0.0 ? (2.0 * sigmoid(x)) - 1.0 : 0.0;
    }


    public static double sigmoid(double x) {
        return (1/( 1 + Math.pow(Math.E,(-x))));
    }


    public void count(Document doc) {
        ThreadState th = node.getThreadState(doc, false);
        if(th == null) return;
        for(Activation act: th.activations.values()) {
            if(act.finalState != null && act.finalState.value > 0.0) {
                activationSum += act.finalState.value;
                numberOfActivations++;
            }
        }
    }


    @Override
    public void write(DataOutput out) throws IOException {
        out.writeBoolean(this instanceof InputNeuron);

        out.writeInt(id);
        out.writeUTF(label);

        out.writeDouble(bias);
        out.writeDouble(negDirSum);
        out.writeDouble(negRecSum);
        out.writeDouble(posRecSum);

        out.writeInt(outputNodes.size());
        for(Map.Entry<Key, InputNode> me: outputNodes.entrySet()) {
            me.getKey().write(out);
            out.writeInt(me.getValue().id);
        }

        out.writeBoolean(node != null);
        if(node != null) {
            out.writeInt(node.id);
        }

        out.writeBoolean(isBlocked);
        out.writeBoolean(noTraining);

        out.writeDouble(activationSum);
        out.writeInt(numberOfActivations);
    }


    @Override
    public void readFields(DataInput in, Document doc) throws IOException {
        id = in.readInt();
        label = in.readUTF();

        bias = in.readDouble();
        negDirSum = in.readDouble();
        negRecSum = in.readDouble();
        posRecSum = in.readDouble();

        int s = in.readInt();
        for(int i = 0; i < s; i++) {
            Key k = Key.read(in, doc);
            InputNode n = (InputNode) doc.m.initialNodes.get(in.readInt());
            outputNodes.put(k, n);
            n.inputNeuron = this;
        }

        if(in.readBoolean()) {
            node = doc.m.initialNodes.get(in.readInt());
            node.neuron = this;
        }

        isBlocked = in.readBoolean();
        noTraining = in.readBoolean();

        activationSum = in.readDouble();
        numberOfActivations = in.readInt();
    }


    public static Neuron read(DataInput in, Document doc) throws IOException {
        Neuron n = in.readBoolean() ? new InputNeuron() : new Neuron();
        n.readFields(in, doc);
        return n;
    }


    @Override
    public int compareTo(Neuron n) {
        if(id < n.id) return -1;
        else if(id > n.id) return 1;
        else return 0;
    }


    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("n(");
        sb.append(id);
        if(label != null) {
            sb.append(",");
            sb.append(label);
        }
        sb.append(")");
        return sb.toString();
    }


    public String toStringWithSynapses() {
        SortedSet<Synapse> is = new TreeSet<>(new Comparator<Synapse>() {
            @Override
            public int compare(Synapse s1, Synapse s2) {
                int r = Double.compare(s2.w, s1.w);
                if(r != 0) return r;
                return Integer.compare(s1.input.id, s2.input.id);
            }
        });

        is.addAll(inputSynapsesByWeight);

        StringBuilder sb = new StringBuilder();
        sb.append(toString());
        sb.append("<");
        sb.append("B:");
        sb.append(Utils.round(bias));
        for(Synapse s: is) {
            sb.append(", ");
            sb.append(Utils.round(s.w));
            sb.append(":");
            sb.append(s.key.relativeRid);
            sb.append(":");
            sb.append(s.input.toString());
        }
        sb.append(">");
        return sb.toString();
    }


    public Collection<Activation> getFinalActivations(Document doc) {
        return Activation.select(doc, node, null, null, null, null, null, null)
                .filter(act -> act.finalState != null && act.finalState.value > 0.0)
                .collect(Collectors.toList());
    }


    public static class NormWeight {
        public final static NormWeight ZERO_WEIGHT = new NormWeight(0.0, 0.0);

        public final double w;
        public final double n;

        private NormWeight(double w, double n) {
            this.w = w;
            this.n = n;
        }

        public static NormWeight create(double w, double n) {
            if(w == 0.0 && n == 0.0) return ZERO_WEIGHT;
            return new NormWeight(w, n);
        }

        public NormWeight add(NormWeight nw) {
            if(nw == null || nw == ZERO_WEIGHT) return this;
            return new NormWeight(w + nw.w, n + nw.n);
        }

        public NormWeight sub(NormWeight nw) {
            if(nw == null || nw == ZERO_WEIGHT) return this;
            return new NormWeight(w - nw.w, n - nw.n);
        }

        public double getNormWeight() {
            return n > 0 ? w / n : 0.0;
        }


        public boolean equals(NormWeight nw) {
            return (Math.abs(w - nw.w) <= Neuron.WEIGHT_TOLERANCE && Math.abs(n - nw.n) <= Neuron.WEIGHT_TOLERANCE);
        }

        public String toString() {
            return "W:" + w + " N:" + n + " NW:" + getNormWeight();
        }
    }

}
