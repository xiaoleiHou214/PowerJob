package com.github.kfcfans.oms.server.service.workflow;

import com.github.kfcfans.oms.common.SystemInstanceResult;
import com.github.kfcfans.oms.common.WorkflowInstanceStatus;
import com.github.kfcfans.oms.common.model.PEWorkflowDAG;
import com.github.kfcfans.oms.common.model.WorkflowDAG;
import com.github.kfcfans.oms.common.utils.JsonUtils;
import com.github.kfcfans.oms.common.utils.WorkflowDAGUtils;
import com.github.kfcfans.oms.server.persistence.core.model.WorkflowInfoDO;
import com.github.kfcfans.oms.server.persistence.core.model.WorkflowInstanceInfoDO;
import com.github.kfcfans.oms.server.persistence.core.repository.WorkflowInstanceInfoRepository;
import com.github.kfcfans.oms.server.service.JobService;
import com.github.kfcfans.oms.server.service.id.IdGenerateService;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Queues;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理运行中的工作流实例
 *
 * @author tjq
 * @since 2020/5/26
 */
@Slf4j
@Service
public class WorkflowInstanceManager {

    @Resource
    private JobService jobService;
    @Resource
    private IdGenerateService idGenerateService;
    @Resource
    private WorkflowInstanceInfoRepository workflowInstanceInfoRepository;

    /**
     * 创建工作流任务实例
     * @param wfInfo 工作流任务元数据（描述信息）
     * @return wfInstanceId
     */
    public Long create(WorkflowInfoDO wfInfo) {

        Long wfInstanceId = idGenerateService.allocate();

        Date now = new Date();
        WorkflowInstanceInfoDO newWfInstance = new WorkflowInstanceInfoDO();
        newWfInstance.setWfInstanceId(wfInstanceId);
        newWfInstance.setWorkflowId(wfInfo.getId());

        newWfInstance.setGmtCreate(now);
        newWfInstance.setGmtModified(now);
        try {

            // 将用于表达的DAG转化为用于计算的DAG
            WorkflowDAG workflowDAG = WorkflowDAGUtils.convert(JsonUtils.parseObject(wfInfo.getPeDAG(), PEWorkflowDAG.class));
            newWfInstance.setDag(JsonUtils.toJSONString(workflowDAG));
            newWfInstance.setStatus(WorkflowInstanceStatus.WAITING.getV());

        }catch (Exception e) {
            log.error("[Workflow-{}] parse PEDag({}) failed.", wfInfo.getId(), wfInfo.getPeDAG(), e);

            newWfInstance.setStatus(WorkflowInstanceStatus.FAILED.getV());
            newWfInstance.setResult(e.getMessage());
        }
        workflowInstanceInfoRepository.save(newWfInstance);
        return wfInstanceId;
    }

    /**
     * 开始任务
     * @param wfInfo 工作流任务信息
     * @param wfInstanceId 工作流任务实例ID
     */
    public void start(WorkflowInfoDO wfInfo, Long wfInstanceId) {

        Optional<WorkflowInstanceInfoDO> wfInstanceInfoOpt = workflowInstanceInfoRepository.findByWfInstanceId(wfInstanceId);
        if (!wfInstanceInfoOpt.isPresent()) {
            log.error("[WorkflowInstanceManager] can't find metadata by workflowInstanceId({}).", wfInstanceId);
            return;
        }
        WorkflowInstanceInfoDO wfInstanceInfo = wfInstanceInfoOpt.get();

        // 不是等待中，不再继续执行（可能上一流程已经失败）
        if (wfInstanceInfo.getStatus() != WorkflowInstanceStatus.WAITING.getV()) {
            log.info("[Workflow-{}] workflowInstance({}) need't running any more.", wfInfo.getId(), wfInstanceInfo);
            return;
        }

        // 并发度控制
        int instanceConcurrency = workflowInstanceInfoRepository.countByWorkflowIdAndStatusIn(wfInfo.getId(), WorkflowInstanceStatus.generalizedRunningStatus);
        if (instanceConcurrency > wfInfo.getMaxWfInstanceNum()) {
            wfInstanceInfo.setStatus(WorkflowInstanceStatus.FAILED.getV());
            wfInstanceInfo.setResult(SystemInstanceResult.TOO_MUCH_INSTANCE);

            workflowInstanceInfoRepository.saveAndFlush(wfInstanceInfo);
            return;
        }

        try {
            WorkflowDAG workflowDAG = WorkflowDAGUtils.convert(JsonUtils.parseObject(wfInfo.getPeDAG(), PEWorkflowDAG.class));

            // 运行根任务，无法找到根任务则直接失败
            WorkflowDAG.Node root = workflowDAG.getRoot();

            // 调度执行根任务
            Long instanceId = jobService.runJob(root.getJobId(), null, wfInstanceId);
            root.setInstanceId(instanceId);

            // 持久化
            wfInstanceInfo.setStatus(WorkflowInstanceStatus.RUNNING.getV());
            wfInstanceInfo.setDag(JsonUtils.toJSONStringUnsafe(workflowDAG));

            log.info("[Workflow-{}] start workflow successfully, wfInstanceId={}", wfInfo.getId(), wfInstanceId);
        }catch (Exception e) {

            wfInstanceInfo.setStatus(WorkflowInstanceStatus.FAILED.getV());
            wfInstanceInfo.setResult(e.getMessage());

            log.error("[Workflow-{}] submit workflow: {} failed.", wfInfo.getId(), wfInfo, e);
        }
        workflowInstanceInfoRepository.saveAndFlush(wfInstanceInfo);
    }

    /**
     * 下一步（当工作流的某个任务完成时调用该方法）
     * @param wfInstanceId 工作流任务实例ID
     * @param instanceId 具体完成任务的某个任务实例ID
     * @param success 完成任务的任务实例是否成功
     * @param result 完成任务的任务实例结果
     */
    public void move(Long wfInstanceId, Long instanceId, boolean success, String result) {

        Optional<WorkflowInstanceInfoDO> wfInstanceInfoOpt = workflowInstanceInfoRepository.findByWfInstanceId(wfInstanceId);
        if (!wfInstanceInfoOpt.isPresent()) {
            log.error("[WorkflowInstanceManager] can't find metadata by workflowInstanceId({}).", wfInstanceId);
            return;
        }
        WorkflowInstanceInfoDO wfInstance = wfInstanceInfoOpt.get();
        Long wfId = wfInstance.getWorkflowId();

        log.debug("[Workflow-{}] one task in dag finished, wfInstanceId={},instanceId={},success={},result={}", wfId, wfInstanceId, instanceId, success, result);

        try {
            WorkflowDAG dag = JsonUtils.parseObject(wfInstance.getDag(), WorkflowDAG.class);

            // 计算是否有新的节点需要派发执行（relyMap 为 自底向上 的映射，用来判断所有父节点是否都已经完成）
            Map<Long, WorkflowDAG.Node> jobId2Node = Maps.newHashMap();
            Multimap<Long, Long> relyMap = LinkedListMultimap.create();

            // 层序遍历 DAG，更新完成节点的状态
            Queue<WorkflowDAG.Node> queue = Queues.newLinkedBlockingQueue();
            queue.add(dag.getRoot());
            while (!queue.isEmpty()) {
                WorkflowDAG.Node head = queue.poll();
                if (instanceId.equals(head.getInstanceId())) {
                    head.setFinished(true);
                    head.setResult(result);

                    log.debug("[Workflow-{}] node(jobId={}) finished in workflowInstance(wfInstanceId={}), success={},result={}", wfId, head.getJobId(), wfInstanceId, success, result);
                }
                queue.addAll(head.getSuccessors());

                jobId2Node.put(head.getJobId(), head);
                head.getSuccessors().forEach(n -> relyMap.put(n.getJobId(), head.getJobId()));
            }

            // 任务失败，DAG流程被打断，整体失败
            if (!success) {
                wfInstance.setDag(JsonUtils.toJSONStringUnsafe(dag));
                wfInstance.setStatus(WorkflowInstanceStatus.FAILED.getV());
                wfInstance.setResult(SystemInstanceResult.ONE_JOB_FAILED);
                workflowInstanceInfoRepository.saveAndFlush(wfInstance);

                log.warn("[Workflow-{}] workflow(wfInstanceId={}) process failed because middle task(instanceId={}) failed", wfId, wfInstanceId, instanceId);
                return;
            }

            // 重新计算需要派发的任务
            AtomicBoolean allFinished = new AtomicBoolean(true);
            relyMap.keySet().forEach(jobId -> {

                // 如果该任务本身已经完成，不需要再计算，直接跳过
                if (jobId2Node.get(jobId).isFinished()) {
                    return;
                }

                allFinished.set(false);
                // 判断某个任务所有依赖的完成情况，只要有一个未完成，即无法执行
                for (Long reliedJobId : relyMap.get(jobId)) {
                    if (!jobId2Node.get(reliedJobId).isFinished()) {
                        return;
                    }
                }

                // 所有依赖已经执行完毕，可以执行该任务
                Map<Long, String> jobId2Result = Maps.newHashMap();
                relyMap.get(jobId).forEach(jid -> jobId2Result.put(jid, jobId2Node.get(jid).getResult()));

                Long newInstanceId = jobService.runJob(jobId, JsonUtils.toJSONString(jobId2Result), wfInstanceId);
                jobId2Node.get(jobId).setInstanceId(newInstanceId);

                log.debug("[Workflow-{}] workflowInstance(wfInstanceId={}) start to process new node(jobId={},instanceId={})", wfId, wfInstanceId, jobId, newInstanceId);
            });

            if (allFinished.get()) {
                wfInstance.setStatus(WorkflowInstanceStatus.SUCCEED.getV());
                // 最终任务的结果作为整个 workflow 的结果
                wfInstance.setResult(result);

                log.info("[Workflow-{}] workflowInstance(wfInstanceId={}) process successfully.", wfId, wfInstanceId);
            }
            wfInstance.setDag(JsonUtils.toJSONString(dag));
            workflowInstanceInfoRepository.saveAndFlush(wfInstance);

        }catch (Exception e) {
            wfInstance.setStatus(WorkflowInstanceStatus.FAILED.getV());
            wfInstance.setResult("MOVE NEXT STEP FAILED: " + e.getMessage());
            workflowInstanceInfoRepository.saveAndFlush(wfInstance);

            log.error("[Workflow-{}] update failed for workflowInstance({}).", wfId, wfInstanceId, e);
        }
    }

}
