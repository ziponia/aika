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
package network.aika.neuron.excitatory;

import network.aika.Config;
import network.aika.Document;
import network.aika.Model;
import network.aika.neuron.*;
import network.aika.neuron.activation.Activation;
import network.aika.neuron.activation.Link;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 *
 * @author Lukas Molzberger
 */
public abstract class ExcitatoryNeuron extends ConjunctiveNeuron<ExcitatorySynapse> {

    private static final Logger log = LoggerFactory.getLogger(ExcitatoryNeuron.class);

    public ExcitatoryNeuron() {
        super();
    }

    public ExcitatoryNeuron(Neuron p) {
        super(p);
    }

    public ExcitatoryNeuron(Model model, String label) {
        super(model, label);
    }

    protected abstract void createCandidateSynapse(Config c, Activation iAct, Activation targetAct);

    public ExcitatorySynapse getMaxInputSynapse() {
        ExcitatorySynapse maxSyn = null;
        for(ExcitatorySynapse s: getInputSynapses()) {
            if(maxSyn == null || maxSyn.getNewWeight() < s.getNewWeight()) {
                maxSyn = s;
            }
        }
        return maxSyn;
    }

    public void train(Config c, Activation act) {
        addDummyLinks(act);
        super.train(c, act);
        createCandidateSynapses(c, act);
    }

    protected void addDummyLinks(Activation act) {
        inputSynapses
                .values()
                .stream()
                .filter(s -> !act.inputLinks.containsKey(s))
                .forEach(s -> new Link(s, null, act).link());
    }

    private void createCandidateSynapses(Config c, Activation targetAct) {
        Document doc = targetAct.getDocument();

        if(log.isDebugEnabled()) {
            log.debug("Created Synapses for Neuron: " + targetAct.getINeuron().getId() + ":" + targetAct.getINeuron().getLabel());
        }

        ArrayList<Activation> candidates = new ArrayList<>();

        targetAct.followDown(doc.getNewVisitedId(), act -> {
            Synapse is = targetAct.getNeuron().getInputSynapse(act.getNeuron());
            if(is != null) return;

            candidates.add(act);
        });

        candidates
                .forEach(act -> createCandidateSynapse(c, act, targetAct));
    }

    public boolean isMature(Config c) {
        Synapse maxSyn = getMaxInputSynapse();
        if(maxSyn == null) {
            return false;
        }

        TSynapse se = (TSynapse) maxSyn;

        return se.getCounts()[1] >= c.getMaturityThreshold();  // Sign.NEG, Sign.POS
    }

    public String typeToString() {
        return "EXCITATORY";
    }
}