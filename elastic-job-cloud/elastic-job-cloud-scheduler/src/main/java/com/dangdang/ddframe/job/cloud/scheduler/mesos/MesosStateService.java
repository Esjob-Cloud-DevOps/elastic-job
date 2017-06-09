/*
 * Copyright 1999-2015 dangdang.com.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * </p>
 */

package com.dangdang.ddframe.job.cloud.scheduler.mesos;

import com.dangdang.ddframe.job.cloud.scheduler.ha.FrameworkIDService;
import com.dangdang.ddframe.job.reg.base.CoordinatorRegistryCenter;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.codehaus.jettison.json.JSONException;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Mesos状态服务.
 * 
 * @author gaohongtao
 * @author liguangyun
 */
@Slf4j
public class MesosStateService {
    
    private final FrameworkIDService frameworkIDService;
    
    public MesosStateService(final CoordinatorRegistryCenter regCenter) {
        frameworkIDService = new FrameworkIDService(regCenter);
    }
    
    /**
     * 获取沙箱信息.
     * 
     * @param appName 作业云配置App的名字
     * @return 沙箱信息
     * @throws JSONException 解析JSON格式异常
     */
    public JsonArray sandbox(final String appName) throws JSONException {
        MesosEndpointService mesosEndpointService = MesosEndpointService.getInstance();
        Optional<JsonObject> state = mesosEndpointService.state(JsonObject.class);
        if (!state.isPresent()) {
            return new JsonArray();
        }
        JsonArray result = new JsonArray();
        for (JsonObject each : findExecutors(state.get().getAsJsonArray("frameworks"), appName)) {
            JsonArray slaves = state.get().getAsJsonArray("slaves");
            String slaveHost = null;
            for (int i = 0; i < slaves.size(); i++) {
                JsonObject slave = slaves.get(i).getAsJsonObject();
                if (each.get("slave_id").getAsString().equals(slave.get("id").getAsString())) {
                    slaveHost = slave.get("pid").getAsString().split("@")[1];
                }
            }
            Preconditions.checkNotNull(slaveHost);
            Optional<JsonObject> slaveState = mesosEndpointService.state(String.format("http://%s", slaveHost), JsonObject.class);
            String workDir = slaveState.get().getAsJsonObject("flags").get("work_dir").getAsString();
            Collection<JsonObject> executorsOnSlave = findExecutors(slaveState.get().getAsJsonArray("frameworks"), appName);
            for (JsonObject executorOnSlave : executorsOnSlave) {
                JsonObject r = new JsonObject();
                r.addProperty("hostname", slaveState.get().get("hostname").getAsString());
                r.addProperty("path", executorOnSlave.get("directory").getAsString().replace(workDir, ""));
                result.add(r);
            }
        }
        return result;
    }
    
    /**
     * 获取任务沙箱信息.
     *
     * @param appName,executorId 作业云配置App名称，执行器主键
     * @return 沙箱信息
     * @throws JSONException 解析JSON格式异常
     */
    public String getTaskSandbox(final String appName, final String executorId) throws JSONException {
        JSONObject state = fetch(stateUrl);
        StringBuilder taskSandbox = new StringBuilder();
        taskSandbox.append(state.getString("pid").split("@")[1]).append("/#/agents/");
        for (JSONObject each : findExecutors(state.getJSONArray("frameworks"), appName)) {
            JSONArray slaves = state.getJSONArray("slaves");
            String slaveHost = null;
            for (int i = 0; i < slaves.length(); i++) {
                JSONObject slave = slaves.getJSONObject(i);
                if (each.getString("slave_id").equals(slave.getString("id"))) {
                    taskSandbox.append(slave.getString("id")).append("/browse?path=");
                    slaveHost = slave.getString("pid").split("@")[1];
                }
            }
            Preconditions.checkNotNull(slaveHost);
            JSONObject slaveState = fetch(String.format("http://%s/state", slaveHost));
            Collection<JSONObject> executorsOnSlave = findExecutors(slaveState.getJSONArray("frameworks"), appName);
            for (JSONObject executorOnSlave : executorsOnSlave) {
                if (executorId.equals(executorOnSlave.get("id"))) {
                    String directory = executorOnSlave.getString("directory");
                    if (null != directory) {
                        taskSandbox.append(directory);
                        return taskSandbox.toString();
                    }
                }
            }
        }
        return Strings.nullToEmpty(null);
    }

    /**
     * 获取主机信息.
     *
     * @param slaveId slave主键
     * @return hostName 主机
     * @throws JSONException 解析JSON格式异常
     */
    public String getFailoverTaskHostname(final String slaveId) throws JSONException {
        JSONObject state = fetch(stateUrl);
        JSONArray slaves = state.getJSONArray("slaves");
        String slaveHost = null;
        for (int i = 0; i < slaves.length(); i++) {
            JSONObject slave = slaves.getJSONObject(i);
            if (slaveId.equals(slave.getString("id"))) {
                slaveHost = slave.getString("hostname");
            }
        }
        return slaveHost;
    }

    /**
     * 查找执行器信息.
     * 
     * @param appName 作业云配置App的名字
     * @return 执行器信息
     * @throws JSONException 解析JSON格式异常
     */
    public Collection<ExecutorStateInfo> executors(final String appName) throws JSONException {
        MesosEndpointService mesosEndpointService = MesosEndpointService.getInstance();
        Optional<JsonObject> jsonObject = mesosEndpointService.state(JsonObject.class);
        if (!jsonObject.isPresent()) {
            Collections.emptyList();
        }
        return Collections2.transform(findExecutors(jsonObject.get().getAsJsonArray("frameworks"), appName), new Function<JsonObject, ExecutorStateInfo>() {
            @Override
            public ExecutorStateInfo apply(final JsonObject input) {
                try {
                    return ExecutorStateInfo.builder().id(getExecutorId(input)).slaveId(input.get("slave_id").getAsString()).build();
                } catch (final JSONException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
    }
    
    /**
     * 获取所有执行器.
     *
     * @return 执行器信息
     * @throws JSONException 解析JSON格式异常
     */
    public Collection<ExecutorStateInfo> executors() throws JSONException {
        return executors(null);
    }
    
    private Collection<JsonObject> findExecutors(final JsonArray frameworks, final String appName) throws JSONException {
        List<JsonObject> result = Lists.newArrayList();
        Optional<String> frameworkIDOptional = frameworkIDService.fetch();
        String frameworkID;
        if (frameworkIDOptional.isPresent()) {
            frameworkID = frameworkIDOptional.get();
        } else {
            return result;
        }
        for (int i = 0; i < frameworks.size(); i++) {
            JsonObject framework = frameworks.get(i).getAsJsonObject();
            if (!framework.get("id").getAsString().equals(frameworkID)) {
                continue;
            }
            JsonArray executors = framework.getAsJsonArray("executors");
            for (int j = 0; j < executors.size(); j++) {
                JsonObject executor = executors.get(j).getAsJsonObject();
                if (null == appName || appName.equals(getExecutorId(executor).split("@-@")[0])) {
                    result.add(executor);
                }
            }
        }
        return result;
    }
    
    private String getExecutorId(final JsonObject executor) throws JSONException {
        return executor.has("id") ? executor.get("id").getAsString() : executor.get("executor_id").getAsString();
    }
    
    @Builder
    @Getter
    public static final class ExecutorStateInfo {
        
        private final String id;
        
        private final String slaveId;
    }
}
