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
package network.aika.neuron;

import network.aika.*;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The {@code NeuronProvider} class is a proxy implementation for the real neuron implementation in the class {@code Neuron}.
 * Aika uses the provider pattern to store and reload rarely used neurons or logic nodes.
 *
 * @author Lukas Molzberger
 */
public class NeuronProvider implements Comparable<NeuronProvider> {

    public static final NeuronProvider MIN_NEURON = new NeuronProvider(null, Long.MIN_VALUE);
    public static final NeuronProvider MAX_NEURON = new NeuronProvider(null, Long.MAX_VALUE);

    public enum SuspensionMode {
        SAVE,
        DISCARD
    }

    private Model model;
    private Long id;

    private volatile Neuron neuron;

    public NeuronProvider(Model model, long id) {
        this.model = model;
        this.id = id;

        if(model != null) {
            model.registerProvider(this);
        }
    }

    public NeuronProvider(Model model, Neuron n) {
        this.model = model;
        this.neuron = n;

        id = model.createNeuronId();
        model.registerProvider(this);
    }

    public Neuron getNeuron() {
        if (neuron == null) {
            reactivate();
        }
        neuron.retrievalCount = model.getCurrentRetrievalCount();
        return neuron;
    }

    public String getDescriptionLabel() {
        return getNeuron().getDescriptionLabel();
    }

    public Long getId() {
        return id;
    }

    public Model getModel() {
        return model;
    }

    public boolean isSuspended() {
        return neuron == null;
    }

    public Neuron getIfNotSuspended() {
        return neuron;
    }

    public synchronized void suspend(SuspensionMode sm) {
        if(neuron == null) return;
        assert model.getSuspensionHook() != null;
        neuron.suspend();

        if(sm == SuspensionMode.SAVE) {
            save();
        }

        neuron = null;
    }

    public void save() {
        if (neuron.isModified()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (
                    GZIPOutputStream gzipos = new GZIPOutputStream(baos);
                    DataOutputStream dos = new DataOutputStream(gzipos)) {

                neuron.write(dos);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            model.getSuspensionHook().store(id, baos.toByteArray());
        }
        neuron.setModified(false);
    }

    private void reactivate() {
        assert model.getSuspensionHook() != null;

        byte[] data = model.getSuspensionHook().retrieve(id);
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        try (
                GZIPInputStream gzipis = new GZIPInputStream(bais);
                DataInputStream dis = new DataInputStream(gzipis);) {
            getModel().readNeuron(dis, this);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        neuron.reactivate();

        model.incrementRetrievalCounter();
    }

    @Override
    public boolean equals(Object o) {
        return id == ((NeuronProvider) o).id;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public int compareTo(NeuronProvider np) {
        return Long.compare(id, np.id);
    }

    public String toString() {
        if(this == MIN_NEURON) return "MIN_NEURON";
        if(this == MAX_NEURON) return "MAX_NEURON";

        return "p(" + id + ":" + (neuron != null ? neuron.toString() : "SUSPENDED") + ")";
    }
}