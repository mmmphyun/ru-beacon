package com.rubeacon.worker.engine

import kotlin.math.min

/**
 * Tarjan의 강결합 컴포넌트(SCC) 알고리즘 기반 인메모리 DAG 순환 참조 검증기.
 * docs/WORKFLOW_ENGINE_SPEC.md §4 규격을 준수함.
 */
object TarjanCycleDetector {

    /**
     * 워크플로우 정의의 모든 노드 및 브랜치 연결 관계를 분석하여 사이클 존재 시 [WorkflowCycleException]을 발생시킴.
     */
    fun validate(definition: WorkflowDefinition) {
        val adjacency = mutableMapOf<String, MutableSet<String>>()

        // 모든 노드 초기화
        for (node in definition.nodes) {
            adjacency[node.id] = mutableSetOf()
        }

        // 엣지 수집 (일반 next_node_ids 및 branches)
        for (node in definition.nodes) {
            val targets = adjacency.getOrPut(node.id) { mutableSetOf() }
            targets.addAll(node.nextNodeIds)
            node.branches?.values?.forEach { branchTargets ->
                targets.addAll(branchTargets)
            }
        }

        // Tarjan SCC 실행
        var index = 0
        val indices = mutableMapOf<String, Int>()
        val lowLink = mutableMapOf<String, Int>()
        val onStack = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        val detectedCycles = mutableListOf<List<String>>()

        fun strongConnect(v: String) {
            indices[v] = index
            lowLink[v] = index
            index++
            stack.addLast(v)
            onStack.add(v)

            for (w in adjacency[v] ?: emptySet()) {
                if (!indices.containsKey(w)) {
                    strongConnect(w)
                    lowLink[v] = min(lowLink[v] ?: index, lowLink[w] ?: index)
                } else if (onStack.contains(w)) {
                    lowLink[v] = min(lowLink[v] ?: index, indices[w] ?: index)
                }
            }

            if (lowLink[v] == indices[v]) {
                val scc = mutableListOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    scc.add(w)
                    if (w == v) break
                }

                // 크기가 2 이상이거나 자기 자신을 향하는 루프가 있는 경우 사이클로 판정
                if (scc.size > 1 || adjacency[v]?.contains(v) == true) {
                    detectedCycles.add(scc)
                }
            }
        }

        for (node in definition.nodes) {
            if (!indices.containsKey(node.id)) {
                strongConnect(node.id)
            }
        }

        if (detectedCycles.isNotEmpty()) {
            val allCycleNodes = detectedCycles.flatten().distinct()
            throw WorkflowCycleException(
                "Cycle detected in workflow definition: $allCycleNodes",
                allCycleNodes
            )
        }
    }
}
