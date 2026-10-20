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

// ============================================================================
// 【厂商补丁 · 阶段 14】类覆盖（class shadowing）修复 ShardingSphere 5.3.2 与
// snakeyaml 2.x（Spring Boot 3.2 自带）的不兼容：
//
//   5.3.2 源码（本文件即其副本）的构造器没有显式 super(...)，
//   编译器插入的 Representer() 无参构造在 snakeyaml 2.0+ 已被删除 →
//   YamlEngine.marshal 抛 NoSuchMethodError: Representer.<init>() （启动后首次建连即崩溃）。
//   官方在 5.5.3 才修复（构造器改为 super(dumperOptions)），5.4.1 仍未修。
//
//   本类与 5.3.2 官方源码逐行一致，仅构造器改为 super(new DumperOptions())（即 5.5.3 的修法）。
//   放置于应用模块（BOOT-INF/classes）→ 由 Spring Boot 类加载器优先命中，
//   覆盖 BOOT-INF/lib 中 jar 内的同名类。随 ShardingSphere 升级到 >= 5.5.3 后应删除本文件。
// ============================================================================

package org.apache.shardingsphere.infra.util.yaml.representer;

import org.apache.shardingsphere.infra.util.spi.ShardingSphereServiceLoader;
import org.apache.shardingsphere.infra.util.yaml.representer.processor.DefaultYamlTupleProcessor;
import org.apache.shardingsphere.infra.util.yaml.representer.processor.ShardingSphereYamlTupleProcessor;
import org.apache.shardingsphere.infra.util.yaml.shortcuts.ShardingSphereYamlShortcuts;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.introspector.Property;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * ShardingSphere YAML representer.（阶段 14 补丁版，见文件头注释）
 */
public final class ShardingSphereYamlRepresenter extends Representer {

    public ShardingSphereYamlRepresenter() {
        // 补丁点：5.3.2 原版为隐式 super()（snakeyaml 2.x 已删除无参构造）；5.5.3 官方修法如下
        super(new DumperOptions());
        Map<String, Class<?>> yamlShortcuts = new HashMap<>();
        ShardingSphereServiceLoader.getServiceInstances(ShardingSphereYamlShortcuts.class).stream().map(ShardingSphereYamlShortcuts::getYamlShortcuts).forEach(yamlShortcuts::putAll);
        yamlShortcuts.forEach((key, value) -> addClassTag(value, new Tag(key)));
    }

    @Override
    protected NodeTuple representJavaBeanProperty(final Object javaBean, final Property property, final Object propertyValue, final Tag customTag) {
        NodeTuple nodeTuple = super.representJavaBeanProperty(javaBean, property, propertyValue, customTag);
        for (ShardingSphereYamlTupleProcessor each : ShardingSphereServiceLoader.getServiceInstances(ShardingSphereYamlTupleProcessor.class)) {
            if (property.getName().equals(each.getTupleName())) {
                return each.process(nodeTuple);
            }
        }
        return new DefaultYamlTupleProcessor().process(nodeTuple);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    protected Node representMapping(final Tag tag, final Map<?, ?> mapping, final DumperOptions.FlowStyle flowStyle) {
        Map skippedEmptyValuesMapping = new LinkedHashMap<>(mapping.size(), 1);
        for (Entry<?, ?> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof Collection && ((Collection) entry.getValue()).isEmpty()) {
                continue;
            }
            if (entry.getValue() instanceof Map && ((Map) entry.getValue()).isEmpty()) {
                continue;
            }
            skippedEmptyValuesMapping.put(entry.getKey(), entry.getValue());
        }
        return super.representMapping(tag, skippedEmptyValuesMapping, flowStyle);
    }
}
