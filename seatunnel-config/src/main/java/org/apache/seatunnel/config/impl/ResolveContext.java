/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.config.impl;

import org.apache.seatunnel.config.ConfigException.BugOrBroken;
import org.apache.seatunnel.config.ConfigResolveOptions;
import org.apache.seatunnel.config.impl.AbstractConfigValue.NotPossibleToResolve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class ResolveContext {
    private final ResolveMemos memos;

    private final ConfigResolveOptions options;
    // the current path restriction, used to ensure lazy
    // resolution and avoid gratuitous cycles. without this,
    // any sibling of an object we're traversing could
    // cause a cycle "by side effect"
    // CAN BE NULL for a full resolve.
    private final Path restrictToChild;

    // This is used for tracing and debugging and nice error messages;
    // contains every node as we call resolve on it.
    private final List<AbstractConfigValue> resolveStack;

    private final Set<AbstractConfigValue> cycleMarkers;

    ResolveContext(ResolveMemos memos, ConfigResolveOptions options, Path restrictToChild,
                   List<AbstractConfigValue> resolveStack, Set<AbstractConfigValue> cycleMarkers) {
        this.memos = memos;
        this.options = options;
        this.restrictToChild = restrictToChild;
        this.resolveStack = Collections.unmodifiableList(resolveStack);
        this.cycleMarkers = Collections.unmodifiableSet(cycleMarkers);
    }

    private static Set<AbstractConfigValue> newCycleMarkers() {
        return Collections.newSetFromMap(new IdentityHashMap<AbstractConfigValue, Boolean>());
    }

    ResolveContext(ConfigResolveOptions options, Path restrictToChild) {
        // LinkedHashSet keeps the traversal order which is at least useful
        // in error messages if nothing else
        this(new ResolveMemos(), options, restrictToChild, new ArrayList<AbstractConfigValue>(), newCycleMarkers());
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(), "ResolveContext restrict to child " + restrictToChild);
        }
    }

    ResolveContext addCycleMarker(AbstractConfigValue value) {
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(), "++ Cycle marker " + value + "@" + System.identityHashCode(value));
        }
        if (cycleMarkers.contains(value)) {
            throw new BugOrBroken("Added cycle marker twice " + value);
        }
        Set<AbstractConfigValue> copy = newCycleMarkers();
        copy.addAll(cycleMarkers);
        copy.add(value);
        return new ResolveContext(memos, options, restrictToChild, resolveStack, copy);
    }

    ResolveContext removeCycleMarker(AbstractConfigValue value) {
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(), "-- Cycle marker " + value + "@" + System.identityHashCode(value));
        }

        Set<AbstractConfigValue> copy = newCycleMarkers();
        copy.addAll(cycleMarkers);
        copy.remove(value);
        return new ResolveContext(memos, options, restrictToChild, resolveStack, copy);
    }

    private ResolveContext memoize(MemoKey key, AbstractConfigValue value) {
        ResolveMemos changed = memos.put(key, value);
        return new ResolveContext(changed, options, restrictToChild, resolveStack, cycleMarkers);
    }

    ConfigResolveOptions options() {
        return options;
    }

    boolean isRestrictedToChild() {
        return restrictToChild != null;
    }

    Path restrictToChild() {
        return restrictToChild;
    }

    // restrictTo may be null to unrestrict
    ResolveContext restrict(Path restrictTo) {
        if (restrictTo == restrictToChild) {
            return this;
        }
        return new ResolveContext(memos, options, restrictTo, resolveStack, cycleMarkers);
    }

    ResolveContext unrestricted() {
        return restrict(null);
    }

    String traceString() {
        String separator = ", ";
        StringBuilder sb = new StringBuilder();
        for (AbstractConfigValue value : resolveStack) {
            if (value instanceof ConfigReference) {
                sb.append(((ConfigReference) value).expression().toString());
                sb.append(separator);
            }
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - separator.length());
        }
        return sb.toString();
    }

    private ResolveContext pushTrace(AbstractConfigValue value) {
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(), "pushing trace " + value);
        }
        List<AbstractConfigValue> copy = new ArrayList<AbstractConfigValue>(resolveStack);
        copy.add(value);
        return new ResolveContext(memos, options, restrictToChild, copy, cycleMarkers);
    }

    ResolveContext popTrace() {
        List<AbstractConfigValue> copy = new ArrayList<AbstractConfigValue>(resolveStack);
        AbstractConfigValue old = copy.remove(resolveStack.size() - 1);
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth() - 1, "popped trace " + old);
        }
        return new ResolveContext(memos, options, restrictToChild, copy, cycleMarkers);
    }

    int depth() {
        if (resolveStack.size() > 30) {
            throw new BugOrBroken("resolve getting too deep");
        }
        return resolveStack.size();
    }

    ResolveResult<? extends AbstractConfigValue> resolve(AbstractConfigValue original, ResolveSource source)
            throws NotPossibleToResolve {
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl
                    .trace(depth(), "resolving "
                            + original + " restrictToChild="
                            + restrictToChild + " in " + source);
        }
        return pushTrace(original).realResolve(original, source).popTrace();
    }

    private ResolveResult<? extends AbstractConfigValue> realResolve(AbstractConfigValue original, ResolveSource source)
            throws NotPossibleToResolve {
        // a fully-resolved (no restrictToChild) object can satisfy a
        // request for a restricted object, so always check that first.
        final MemoKey fullKey = new MemoKey(original, null);
        MemoKey restrictedKey = null;

        AbstractConfigValue cached = memos.get(fullKey);

        // but if there was no fully-resolved object cached, we'll only
        // compute the restrictToChild object so use a more limited
        // memo key
        if (cached == null && isRestrictedToChild()) {
            restrictedKey = new MemoKey(original, restrictToChild());
            cached = memos.get(restrictedKey);
        }

        if (cached != null) {
            if (ConfigImpl.traceSubSituationsEnable()) {
                ConfigImpl.trace(depth(), "using cached resolution "
                        + cached + " for "
                        + original + " restrictToChild "
                        + restrictToChild());
            }
            return ResolveResult.make(this, cached);
        }
        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(),
                    "not found in cache, resolving " + original + "@" + System.identityHashCode(original));
        }

        if (cycleMarkers.contains(original)) {
            if (ConfigImpl.traceSubSituationsEnable()) {
                ConfigImpl.trace(depth(),
                        "Cycle detected, can't resolve; " + original + "@" + System.identityHashCode(original));
            }
            throw new NotPossibleToResolve(this);
        }

        ResolveResult<? extends AbstractConfigValue> result = original.resolveSubstitutions(this, source);
        AbstractConfigValue resolved = result.value;

        if (ConfigImpl.traceSubSituationsEnable()) {
            ConfigImpl.trace(depth(), "resolved to "
                    + resolved + "@"
                    + System.identityHashCode(resolved)
                    + " from " + original
                    + "@" + System.identityHashCode(resolved));
        }

        ResolveContext withMemo = result.context;

        if (resolved == null || resolved.resolveStatus() == ResolveStatus.RESOLVED) {
            // if the resolved object is fully resolved by resolving
            // only the restrictToChildOrNull, then it can be cached
            // under fullKey since the child we were restricted to
            // turned out to be the only unresolved thing.
            if (ConfigImpl.traceSubSituationsEnable()) {
                ConfigImpl.trace(depth(), "caching " + fullKey + " result " + resolved);
            }

            withMemo = withMemo.memoize(fullKey, resolved);
        } else {
            // if we have an unresolved object then either we did a
            // partial resolve restricted to a certain child, or we are
            // allowing incomplete resolution, or it's a bug.
            if (isRestrictedToChild()) {
                if (ConfigImpl.traceSubSituationsEnable()) {
                    ConfigImpl.trace(depth(), "caching " + restrictedKey + " result " + resolved);
                }

                withMemo = withMemo.memoize(restrictedKey, resolved);
            } else if (options().getAllowUnresolved()) {
                if (ConfigImpl.traceSubSituationsEnable()) {
                    ConfigImpl.trace(depth(), "caching " + fullKey + " result " + resolved);
                }

                withMemo = withMemo.memoize(fullKey, resolved);
            } else {
                throw new BugOrBroken(
                        "resolveSubstitutions() did not give us a resolved object");
            }
        }

        return ResolveResult.make(withMemo, resolved);
    }

    static AbstractConfigValue resolve(AbstractConfigValue value, AbstractConfigObject root,
                                       ConfigResolveOptions options) {
        ResolveSource source = new ResolveSource(root);
        ResolveContext context = new ResolveContext(options, null /* restrictToChild */);

        try {
            return context.resolve(value, source).value;
        } catch (NotPossibleToResolve e) {
            // ConfigReference was supposed to catch NotPossibleToResolve
            throw new BugOrBroken(
                    "NotPossibleToResolve was thrown from an outermost resolve", e);
        }
    }
}
