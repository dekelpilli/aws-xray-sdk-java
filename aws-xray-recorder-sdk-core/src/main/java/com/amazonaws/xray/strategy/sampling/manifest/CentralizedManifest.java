package com.amazonaws.xray.strategy.sampling.manifest;

import com.amazonaws.services.xray.model.SamplingRule;
import com.amazonaws.services.xray.model.SamplingStatisticsDocument;
import com.amazonaws.services.xray.model.SamplingTargetDocument;
import com.amazonaws.xray.strategy.sampling.CentralizedSamplingStrategy;
import com.amazonaws.xray.strategy.sampling.SamplingRequest;
import com.amazonaws.xray.strategy.sampling.rand.RandImpl;
import com.amazonaws.xray.strategy.sampling.rule.CentralizedRule;
import com.amazonaws.xray.strategy.sampling.rule.Rule;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.time.Instant;
import java.util.*;

public class CentralizedManifest implements Manifest {
    private static final Log logger =
            LogFactory.getLog(CentralizedManifest.class);

    private static final long TTL = 3600; // Seconds

    // Map of customer-defined rules. Does not include customer default rule. Sorted by rule priority.
    private volatile LinkedHashMap<String, CentralizedRule> rules;

    // Customer default rule that matches against everything.
    private volatile CentralizedRule defaultRule;

    // Timestamp of last known valid refresh. Kept volatile for swapping with new timestamp on refresh.
    private volatile Instant refreshedAt;

    public CentralizedManifest() {
        this.rules = new LinkedHashMap<>(0);
        this.refreshedAt = Instant.EPOCH;
    }

    public LinkedHashMap<String, CentralizedRule> getRules() {
        return rules;
    }

    public CentralizedRule getDefaultRule() {
        return defaultRule;
    }

    public boolean isExpired(Instant now) {
        return refreshedAt.plusSeconds(TTL).isBefore(now);
    }

    public int size() {
        if (defaultRule != null) {
            return rules.size() + 1;
        }

        return rules.size();
    }

    public Rule match(SamplingRequest req, Instant now) {
        for (CentralizedRule r : rules.values()) {
            if (!r.match(req)) {
                continue;
            }

            return r;
        }

        CentralizedRule r = defaultRule;
        if (r != null) {
            return r;
        }

        return null;
    }

    public void putRules(List<SamplingRule> inputs, Instant now) {
        // Set to true if we see a new or deleted rule or a change in the priority of an existing rule.
        boolean invalidate = false;

        Map<String, CentralizedRule> rules = this.rules;

        for (SamplingRule i : inputs) {
            if (i.getRuleName().equals(CentralizedRule.DEFAULT_RULE_NAME)) {
                putDefaultRule(i);
            } else {
                invalidate = putCustomRule(rules, i);
            }
        }

        if (invalidate) {
            this.rules = rebuild(rules, inputs);
        }

        this.refreshedAt = now;
    }

    public List<SamplingStatisticsDocument> snapshots(Instant now) {
        List<SamplingStatisticsDocument> snapshots = new ArrayList<>(rules.size() + 1);
        Date date = Date.from(now);

        for (CentralizedRule rule : rules.values()) {
            if (!rule.isStale(now)) {
                continue;
            }

            SamplingStatisticsDocument snapshot = rule.snapshot(date);
            snapshot.withClientID(CentralizedSamplingStrategy.getClientID());

            snapshots.add(snapshot);
        }

        if (defaultRule != null && defaultRule.isStale(now)) {
            SamplingStatisticsDocument snapshot = defaultRule.snapshot(date);
            snapshot.withClientID(CentralizedSamplingStrategy.getClientID());

            snapshots.add(snapshot);
        }

        return snapshots;
    }

    public void putTargets(List<SamplingTargetDocument> targets, Instant now) {
        Map<String, CentralizedRule> rules = this.rules;

        for (SamplingTargetDocument t : targets) {
            CentralizedRule r = null;

            if (rules.containsKey(t.getRuleName())) {
                r = rules.get(t.getRuleName());
            } else if (t.getRuleName().equals(CentralizedRule.DEFAULT_RULE_NAME)) {
                r = defaultRule;
            }

            if (r == null) {
                continue;
            }

            r.update(t, now);
        }
    }

    private boolean putCustomRule(Map<String, CentralizedRule> rules, SamplingRule i) {
        CentralizedRule r = rules.get(i.getRuleName());
        if (r == null) {
            return true;
        }

        return r.update(i);
    }

    private void putDefaultRule(SamplingRule i) {
        if (defaultRule == null) {
            defaultRule = new CentralizedRule(i, new RandImpl());
        } else {
            defaultRule.update(i);
        }
    }

    LinkedHashMap<String, CentralizedRule> rebuild(Map<String, CentralizedRule> old, List<SamplingRule> inputs) {
        List<CentralizedRule> rules = new ArrayList<>(inputs.size() - 1);

        for (SamplingRule i : inputs) {
            if (i.getRuleName().equals(CentralizedRule.DEFAULT_RULE_NAME)) {
                continue;
            }
            CentralizedRule r;

            if (old.containsKey(i.getRuleName())) {
                r = old.get(i.getRuleName());
            } else {
                r = new CentralizedRule(i, new RandImpl());
            }

            rules.add(r);
        }
        Collections.sort(rules);

        LinkedHashMap<String, CentralizedRule> current = new LinkedHashMap<>(rules.size());
        for (CentralizedRule r: rules) {
            current.put(r.getName(), r);
        }

        return current;
    }
}
