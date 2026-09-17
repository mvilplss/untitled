package org.example.agent;

import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;

class AgentEntry {

    final AgentSpec spec;
    final Model model;
    final HarnessAgent agent;

    AgentEntry(AgentSpec spec, Model model, HarnessAgent agent) {
        this.spec = spec;
        this.model = model;
        this.agent = agent;
    }
}