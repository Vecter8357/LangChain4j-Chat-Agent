(() => {
    'use strict';

    const NODE_WIDTH = 196;
    const NODE_HEIGHT = 88;
    const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED']);
    const state = {
        catalog: null,
        design: null,
        selected: {kind: 'workflow', id: null},
        connectFrom: null,
        dirty: true,
        validationErrors: [],
        run: null,
        pollTimer: null,
        definitions: []
    };

    const $ = id => document.getElementById(id);
    const elements = {};

    document.addEventListener('DOMContentLoaded', init);

    async function init() {
        cacheElements();
        bindStaticEvents();
        try {
            state.catalog = await request('/api/dag-workflows/catalog');
            renderPalette();
            await refreshDefinitions();
            newDesign(false);
            await refreshHistory();
        } catch (error) {
            notify(error.message, true);
        }
    }

    function cacheElements() {
        [
            'definition-select', 'new-button', 'import-button', 'export-button', 'validate-button',
            'save-button', 'run-button', 'import-file', 'node-palette', 'workflow-title',
            'dirty-indicator', 'workflow-settings-button', 'fit-button', 'delete-button',
            'canvas-viewport', 'workflow-canvas', 'edge-layer', 'node-layer', 'inspector-caption',
            'inspector-content', 'run-status', 'run-id', 'run-input', 'validation-result',
            'run-events', 'node-run-details', 'run-history', 'refresh-history-button',
            'toggle-debug-button', 'debug-body', 'toast'
        ].forEach(id => elements[camel(id)] = $(id));
    }

    function camel(value) {
        return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    }

    function bindStaticEvents() {
        elements.newButton.addEventListener('click', () => newDesign(true));
        elements.validateButton.addEventListener('click', validateDefinition);
        elements.saveButton.addEventListener('click', saveDefinition);
        elements.runButton.addEventListener('click', runWorkflow);
        elements.exportButton.addEventListener('click', exportDesign);
        elements.importButton.addEventListener('click', () => elements.importFile.click());
        elements.importFile.addEventListener('change', importDesign);
        elements.definitionSelect.addEventListener('change', event => loadDefinition(event.target.value));
        elements.workflowSettingsButton.addEventListener('click', () => select('workflow', null));
        elements.deleteButton.addEventListener('click', deleteSelected);
        elements.fitButton.addEventListener('click', fitNodes);
        elements.workflowCanvas.addEventListener('click', event => {
            if (event.target === elements.workflowCanvas || event.target === elements.nodeLayer) {
                state.connectFrom = null;
                select('workflow', null);
            }
        });
        elements.workflowCanvas.addEventListener('keydown', event => {
            if ((event.key === 'Delete' || event.key === 'Backspace')
                    && !['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement.tagName)) {
                deleteSelected();
            }
        });
        elements.refreshHistoryButton.addEventListener('click', refreshHistory);
        elements.runHistory.addEventListener('change', event => {
            if (event.target.value) loadRun(event.target.value);
        });
        elements.toggleDebugButton.addEventListener('click', toggleDebug);
        window.addEventListener('resize', renderEdges);
    }

    function newDesign(confirmDiscard) {
        if (confirmDiscard && state.dirty && !window.confirm('当前草稿尚未保存，确定新建吗？')) return;
        clearPoll();
        const suffix = Date.now().toString().slice(-6);
        state.design = {
            status: 'DRAFT',
            definition: {
                code: `workflow-${suffix}`,
                name: '未命名工作流',
                version: 1,
                inputSchema: {
                    type: 'object',
                    properties: {sourceText: {type: 'string'}}
                },
                nodes: [
                    nodeFromTemplate('START', 'start', '开始'),
                    nodeFromTemplate('END', 'end', '结束')
                ],
                edges: [{id: 'edge-start-end', from: 'start', to: 'end'}],
                outputMappings: {}
            },
            positions: {
                start: {x: 140, y: 180},
                end: {x: 520, y: 180}
            }
        };
        state.run = null;
        state.validationErrors = [];
        state.selected = {kind: 'workflow', id: null};
        elements.definitionSelect.value = '';
        markDirty();
        renderAll();
    }

    function nodeFromTemplate(type, id, name) {
        const template = state.catalog?.nodeTypes?.find(item => item.type === type);
        return {
            id,
            name: name || template?.label || type,
            type,
            joinMode: 'ANY',
            inputMappings: {},
            config: clone(template?.defaultConfig || {}),
            timeoutSeconds: 120,
            retryPolicy: {maxAttempts: 1, backoffMillis: 0}
        };
    }

    function renderAll() {
        if (!state.design) return;
        elements.workflowTitle.textContent = state.design.definition.name || state.design.definition.code;
        elements.dirtyIndicator.textContent = state.dirty ? '未保存' : '已保存';
        elements.dirtyIndicator.classList.toggle('saved', !state.dirty);
        renderCanvas();
        renderInspector();
        renderDebug();
    }

    function renderPalette() {
        elements.nodePalette.innerHTML = state.catalog.nodeTypes.map(template => `
            <button class="palette-node" type="button" data-node-type="${escapeHtml(template.type)}">
                <span class="palette-node-icon">${escapeHtml(shortType(template.type))}</span>
                <span><strong>${escapeHtml(template.label)}</strong><small>${escapeHtml(template.description)}</small></span>
            </button>`).join('');
        elements.nodePalette.querySelectorAll('[data-node-type]').forEach(button => {
            button.addEventListener('click', () => addNode(button.dataset.nodeType));
        });
    }

    function shortType(type) {
        return ({START: 'S', END: 'E', LLM: 'LLM', AGENT: 'A', MCP_TOOL: 'MCP', CONDITION: '?'})[type] || type;
    }

    function addNode(type) {
        if (['START', 'END'].includes(type) && state.design.definition.nodes.some(node => node.type === type)) {
            notify(`${type} 节点只能有一个`, true);
            return;
        }
        const base = type.toLowerCase().replace('_tool', '');
        const id = uniqueNodeId(base);
        const template = state.catalog.nodeTypes.find(item => item.type === type);
        const node = nodeFromTemplate(type, id, template?.label);
        state.design.definition.nodes.push(node);
        const viewport = elements.canvasViewport;
        const offset = state.design.definition.nodes.length * 24;
        state.design.positions[id] = {
            x: Math.min(2050, viewport.scrollLeft + 250 + offset),
            y: Math.min(1200, viewport.scrollTop + 120 + offset)
        };
        markDirty();
        select('node', id);
    }

    function uniqueNodeId(base) {
        const ids = new Set(state.design.definition.nodes.map(node => node.id));
        let index = 1;
        let candidate = base;
        while (ids.has(candidate)) candidate = `${base}-${++index}`;
        return candidate;
    }

    function renderCanvas() {
        const runNodes = state.run?.nodeRuns || {};
        const errorNodeIds = new Set(state.validationErrors
            .filter(issue => issue.scope === 'NODE').map(issue => issue.targetId));
        elements.nodeLayer.innerHTML = state.design.definition.nodes.map(node => {
            const position = state.design.positions[node.id] || {x: 80, y: 80};
            const selected = state.selected.kind === 'node' && state.selected.id === node.id;
            const runNode = runNodes[node.id];
            const status = runNode?.status || 'PENDING';
            return `<article class="workflow-node ${selected ? 'selected' : ''} ${errorNodeIds.has(node.id) ? 'validation-error' : ''} status-${status}"
                    data-node-id="${escapeHtml(node.id)}" style="left:${position.x}px;top:${position.y}px">
                <button class="node-port input" type="button" title="连接到此节点" ${node.type === 'START' ? 'hidden' : ''}></button>
                <div class="node-header"><strong>${escapeHtml(node.name || node.id)}</strong><span class="node-type">${escapeHtml(node.type)}</span></div>
                <div class="node-body"><span>${escapeHtml(node.id)}</span><span class="node-status">${escapeHtml(status)}</span></div>
                <button class="node-port output ${state.connectFrom === node.id ? 'connecting' : ''}" type="button" title="从此节点连线" ${node.type === 'END' ? 'hidden' : ''}></button>
            </article>`;
        }).join('');

        elements.nodeLayer.querySelectorAll('.workflow-node').forEach(element => {
            const nodeId = element.dataset.nodeId;
            element.addEventListener('click', event => {
                event.stopPropagation();
                select('node', nodeId);
            });
            element.querySelector('.node-header').addEventListener('pointerdown', event => startDrag(event, nodeId, element));
            element.querySelector('.node-port.output')?.addEventListener('click', event => {
                event.stopPropagation();
                state.connectFrom = state.connectFrom === nodeId ? null : nodeId;
                renderCanvas();
            });
            element.querySelector('.node-port.input')?.addEventListener('click', event => {
                event.stopPropagation();
                completeConnection(nodeId);
            });
        });
        renderEdges();
    }

    function startDrag(event, nodeId, element) {
        if (event.button !== 0) return;
        event.preventDefault();
        const start = {x: event.clientX, y: event.clientY};
        const original = {...state.design.positions[nodeId]};
        element.setPointerCapture?.(event.pointerId);
        const move = moveEvent => {
            const x = Math.max(10, Math.min(2190, original.x + moveEvent.clientX - start.x));
            const y = Math.max(10, Math.min(1290, original.y + moveEvent.clientY - start.y));
            state.design.positions[nodeId] = {x, y};
            element.style.left = `${x}px`;
            element.style.top = `${y}px`;
            renderEdges();
        };
        const end = () => {
            document.removeEventListener('pointermove', move);
            document.removeEventListener('pointerup', end);
            markDirty();
        };
        document.addEventListener('pointermove', move);
        document.addEventListener('pointerup', end, {once: true});
    }

    function completeConnection(targetId) {
        const sourceId = state.connectFrom;
        if (!sourceId) {
            notify('请先点击来源节点右侧的输出点');
            return;
        }
        if (sourceId === targetId) {
            notify('节点不能连接自身', true);
            return;
        }
        const exists = state.design.definition.edges.some(edge => edge.from === sourceId && edge.to === targetId);
        if (exists) {
            notify('该连线已经存在', true);
            state.connectFrom = null;
            renderCanvas();
            return;
        }
        state.design.definition.edges.push({
            id: uniqueEdgeId(sourceId, targetId),
            from: sourceId,
            to: targetId
        });
        state.connectFrom = null;
        markDirty();
        select('edge', `${sourceId}->${targetId}`);
    }

    function uniqueEdgeId(from, to) {
        const used = new Set(state.design.definition.edges.map(edge => edge.id));
        let candidate = `edge-${from}-${to}`;
        let index = 1;
        while (used.has(candidate)) candidate = `edge-${from}-${to}-${++index}`;
        return candidate;
    }

    function renderEdges() {
        if (!state.design) return;
        const errorEdges = new Set(state.validationErrors
            .filter(issue => issue.scope === 'EDGE').map(issue => issue.targetId));
        const paths = state.design.definition.edges.map(edge => {
            const from = state.design.positions[edge.from];
            const to = state.design.positions[edge.to];
            if (!from || !to) return '';
            const x1 = from.x + NODE_WIDTH;
            const y1 = from.y + NODE_HEIGHT / 2;
            const x2 = to.x;
            const y2 = to.y + NODE_HEIGHT / 2;
            const bend = Math.max(70, Math.abs(x2 - x1) * .45);
            const path = `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`;
            const key = edgeKey(edge);
            const selected = state.selected.kind === 'edge' && state.selected.id === key;
            return `<path class="edge-hit" data-edge-key="${escapeHtml(key)}" d="${path}"></path>
                <path class="workflow-edge ${selected ? 'selected' : ''} ${errorEdges.has(key) ? 'error' : ''}" d="${path}"></path>`;
        }).join('');
        elements.edgeLayer.innerHTML = `<defs><marker id="edge-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="currentColor"></path></marker></defs>${paths}`;
        elements.edgeLayer.querySelectorAll('.edge-hit').forEach(path => {
            path.addEventListener('click', event => {
                event.stopPropagation();
                select('edge', path.dataset.edgeKey);
            });
        });
    }

    function edgeKey(edge) {
        return `${edge.from}->${edge.to}`;
    }

    function select(kind, id) {
        state.selected = {kind, id};
        elements.deleteButton.disabled = kind === 'workflow';
        renderCanvas();
        renderInspector();
        renderNodeRunDetails();
    }

    function renderInspector() {
        if (!state.design) return;
        if (state.selected.kind === 'node') return renderNodeInspector(findNode(state.selected.id));
        if (state.selected.kind === 'edge') return renderEdgeInspector(findEdge(state.selected.id));
        return renderWorkflowInspector();
    }

    function renderWorkflowInspector() {
        const definition = state.design.definition;
        elements.inspectorCaption.textContent = '工作流';
        elements.inspectorContent.innerHTML = `
            <div class="form-section">
                <div class="form-section-title">基础信息</div>
                ${field('工作流编码', 'workflow-code', definition.code)}
                ${field('名称', 'workflow-name', definition.name || '')}
                <div class="form-grid">
                    ${field('版本', 'workflow-version', definition.version, 'number')}
                    <div class="form-row"><label>状态</label><select id="workflow-status">
                        ${['DRAFT', 'PUBLISHED', 'DISABLED'].map(value => `<option ${state.design.status === value ? 'selected' : ''}>${value}</option>`).join('')}
                    </select></div>
                </div>
            </div>
            <div class="form-section">
                <div class="form-section-title">输入与输出</div>
                ${textareaField('工作流输入 JSON Schema', 'workflow-input-schema', pretty(definition.inputSchema || {}))}
                ${textareaField('最终输出映射 JSON', 'workflow-output-mappings', pretty(definition.outputMappings || {}))}
                <p class="muted-help">输出映射示例：{"answer":{"path":"$.nodes.end.output.answer"}}</p>
            </div>`;
        $('workflow-code').addEventListener('input', event => updateWorkflow('code', event.target.value));
        $('workflow-name').addEventListener('input', event => updateWorkflow('name', event.target.value));
        $('workflow-version').addEventListener('change', event => updateWorkflow('version', Math.max(1, Number(event.target.value) || 1)));
        $('workflow-status').addEventListener('change', event => { state.design.status = event.target.value; markDirty(); });
        bindJsonTextarea('workflow-input-schema', value => state.design.definition.inputSchema = value);
        bindJsonTextarea('workflow-output-mappings', value => state.design.definition.outputMappings = value);
    }

    function updateWorkflow(fieldName, value) {
        state.design.definition[fieldName] = value;
        markDirty();
        elements.workflowTitle.textContent = state.design.definition.name || state.design.definition.code;
    }

    function renderNodeInspector(node) {
        if (!node) return renderWorkflowInspector();
        elements.inspectorCaption.textContent = `${node.type} · ${node.id}`;
        const specific = renderSpecificNodeFields(node);
        elements.inspectorContent.innerHTML = `
            <div class="form-section">
                <div class="form-section-title">节点信息</div>
                ${field('节点 ID', 'node-id', node.id, 'text', true)}
                ${field('显示名称', 'node-name', node.name || '')}
                <div class="form-grid">
                    <div class="form-row"><label>汇聚方式</label><select id="node-join-mode">
                        ${state.catalog.joinModes.map(mode => `<option ${node.joinMode === mode ? 'selected' : ''}>${mode}</option>`).join('')}
                    </select></div>
                    ${field('超时秒数', 'node-timeout', node.timeoutSeconds || 120, 'number')}
                </div>
                <div class="form-grid">
                    ${field('最大尝试', 'node-attempts', node.retryPolicy?.maxAttempts || 1, 'number')}
                    ${field('退避毫秒', 'node-backoff', node.retryPolicy?.backoffMillis || 0, 'number')}
                </div>
            </div>
            ${specific}
            <div class="form-section">
                <div class="form-section-title">输入映射</div>
                ${renderMappings(node)}
                <button id="add-mapping-button" class="small-action" type="button">+ 添加输入</button>
                <p class="muted-help">路径建议只展示当前节点可访问的工作流输入及上游节点输出，也可手工输入。</p>
            </div>`;

        $('node-name').addEventListener('input', event => {
            node.name = event.target.value; markDirty(); renderCanvas();
        });
        $('node-join-mode').addEventListener('change', event => { node.joinMode = event.target.value; markDirty(); });
        $('node-timeout').addEventListener('change', event => { node.timeoutSeconds = Math.max(1, Number(event.target.value) || 120); markDirty(); });
        $('node-attempts').addEventListener('change', event => { node.retryPolicy.maxAttempts = Math.max(1, Number(event.target.value) || 1); markDirty(); });
        $('node-backoff').addEventListener('change', event => { node.retryPolicy.backoffMillis = Math.max(0, Number(event.target.value) || 0); markDirty(); });
        bindSpecificNodeFields(node);
        bindMappings(node);
    }

    function renderSpecificNodeFields(node) {
        const config = node.config || (node.config = {});
        if (node.type === 'START') {
            return `<div class="form-section"><div class="form-section-title">工作流输入</div>
                ${textareaField('输入 JSON Schema', 'start-input-schema', pretty(state.design.definition.inputSchema || {}))}</div>`;
        }
        if (node.type === 'LLM') {
            return `<div class="form-section"><div class="form-section-title">模型配置</div>
                <div class="form-row"><label>模型角色</label><select id="llm-role">
                    ${state.catalog.modelRoles.map(role => `<option ${config.role === role ? 'selected' : ''}>${role}</option>`).join('')}
                </select></div>
                ${textareaField('指令', 'llm-instruction', config.instruction || '')}
                <div class="form-row"><label>响应模式</label><select id="llm-response-mode">
                    ${['TEXT', 'JSON_SCHEMA'].map(mode => `<option ${config.responseMode === mode ? 'selected' : ''}>${mode}</option>`).join('')}
                </select></div>
                ${textareaField('输出 JSON Schema（可选）', 'node-output-schema', pretty(config.outputSchema || {}))}
            </div>`;
        }
        if (node.type === 'AGENT') {
            const selected = new Set(config.capabilityIds || []);
            return `<div class="form-section"><div class="form-section-title">Agent 配置</div>
                ${textareaField('System Prompt', 'agent-system-prompt', config.systemPrompt || '')}
                ${textareaField('默认目标', 'agent-objective', config.objective || '')}
                ${field('最大步骤', 'agent-max-steps', config.maxSteps || 6, 'number')}
                <div class="form-row"><label>授权能力</label><div class="capability-list">
                    ${state.catalog.capabilities.map(capability => `<label class="capability-item">
                        <input type="checkbox" data-capability-id="${escapeHtml(capability.id)}" ${selected.has(capability.id) ? 'checked' : ''}>
                        <span>${escapeHtml(capability.name)}<small>${escapeHtml(capability.id)} · ${escapeHtml(capability.type)}</small></span>
                    </label>`).join('') || '<span class="muted-help">当前没有已注册能力</span>'}
                </div></div>
            </div>`;
        }
        if (node.type === 'MCP_TOOL') {
            const mcpCapabilities = state.catalog.capabilities.filter(item => item.type === 'MCP_TOOL');
            return `<div class="form-section"><div class="form-section-title">MCP Tool 配置</div>
                <div class="form-row"><label>Capability</label><select id="mcp-capability">
                    <option value="">兼容 research 操作</option>
                    ${mcpCapabilities.map(capability => `<option value="${escapeHtml(capability.id)}" ${config.capabilityId === capability.id ? 'selected' : ''}>${escapeHtml(capability.name)} · ${escapeHtml(capability.id)}</option>`).join('')}
                </select></div>
                ${field('兼容操作', 'mcp-operation', config.operation || 'research')}
                <p class="muted-help">选择 Capability 后，节点输入将直接作为 Tool 参数；未选择时只支持 research 兼容操作。</p>
            </div>`;
        }
        if (node.type === 'CONDITION') {
            return `<div class="form-section"><div class="form-section-title">条件节点</div>
                <p class="muted-help">该节点透传输入。请选择输出连线，在连线配置中设置判断路径、运算符和比较值。</p></div>`;
        }
        if (node.type === 'END') {
            return `<div class="form-section"><div class="form-section-title">结束节点</div>
                <p class="muted-help">结束节点输出默认来自下方输入映射；也可以在“工作流设置”中配置最终 outputMappings。</p></div>`;
        }
        return '';
    }

    function bindSpecificNodeFields(node) {
        if (node.type === 'START') {
            bindJsonTextarea('start-input-schema', value => state.design.definition.inputSchema = value);
        } else if (node.type === 'LLM') {
            $('llm-role').addEventListener('change', event => { node.config.role = event.target.value; markDirty(); });
            $('llm-instruction').addEventListener('input', event => { node.config.instruction = event.target.value; markDirty(); });
            $('llm-response-mode').addEventListener('change', event => { node.config.responseMode = event.target.value; markDirty(); });
            bindJsonTextarea('node-output-schema', value => node.config.outputSchema = value);
        } else if (node.type === 'AGENT') {
            $('agent-system-prompt').addEventListener('input', event => { node.config.systemPrompt = event.target.value; markDirty(); });
            $('agent-objective').addEventListener('input', event => { node.config.objective = event.target.value; markDirty(); });
            $('agent-max-steps').addEventListener('change', event => { node.config.maxSteps = Math.max(1, Number(event.target.value) || 6); markDirty(); });
            elements.inspectorContent.querySelectorAll('[data-capability-id]').forEach(input => {
                input.addEventListener('change', () => {
                    node.config.capabilityIds = [...elements.inspectorContent.querySelectorAll('[data-capability-id]:checked')]
                        .map(item => item.dataset.capabilityId);
                    markDirty();
                });
            });
        } else if (node.type === 'MCP_TOOL') {
            $('mcp-capability').addEventListener('change', event => {
                if (event.target.value) node.config.capabilityId = event.target.value;
                else delete node.config.capabilityId;
                markDirty();
            });
            $('mcp-operation').addEventListener('input', event => { node.config.operation = event.target.value; markDirty(); });
        }
    }

    function renderMappings(node) {
        const mappings = Object.entries(node.inputMappings || {});
        const paths = availablePaths(node.id);
        return `<datalist id="available-paths">${paths.map(path => `<option value="${escapeHtml(path)}"></option>`).join('')}</datalist>
            <div id="mapping-list" class="mapping-list">${mappings.map(([name, binding], index) => mappingRow(name, binding, index)).join('') || '<span class="muted-help empty-mappings">尚未配置输入映射。</span>'}</div>`;
    }

    function mappingRow(name, binding, index) {
        const mode = binding.path ? 'path' : 'value';
        const source = binding.path || pretty(binding.value ?? '');
        return `<div class="mapping-row" data-mapping-index="${index}">
            <div class="mapping-row-top"><input data-mapping-name value="${escapeHtml(name)}" placeholder="输入字段名"><button class="icon-button" data-remove-mapping type="button">×</button></div>
            <div class="mapping-row-source"><select data-mapping-mode><option value="path" ${mode === 'path' ? 'selected' : ''}>变量</option><option value="value" ${mode === 'value' ? 'selected' : ''}>常量</option></select>
            <input data-mapping-source list="available-paths" value="${escapeHtml(source)}" placeholder="$.nodes.start.output.text"></div>
            <label class="switch-row"><input data-mapping-required type="checkbox" ${binding.required !== false ? 'checked' : ''}>必填</label>
        </div>`;
    }

    function bindMappings(node) {
        const sync = () => {
            const mappings = {};
            elements.inspectorContent.querySelectorAll('.mapping-row').forEach(row => {
                const name = row.querySelector('[data-mapping-name]').value.trim();
                if (!name) return;
                const mode = row.querySelector('[data-mapping-mode]').value;
                const source = row.querySelector('[data-mapping-source]').value.trim();
                const binding = {required: row.querySelector('[data-mapping-required]').checked};
                if (mode === 'path') binding.path = source;
                else binding.value = parseJsonOrText(source);
                mappings[name] = binding;
            });
            node.inputMappings = mappings;
            markDirty();
        };
        elements.inspectorContent.querySelectorAll('.mapping-row input, .mapping-row select').forEach(input => input.addEventListener('change', sync));
        elements.inspectorContent.querySelectorAll('[data-remove-mapping]').forEach(button => {
            button.addEventListener('click', () => {
                button.closest('.mapping-row').remove(); sync(); renderNodeInspector(node);
            });
        });
        $('add-mapping-button').addEventListener('click', () => {
            const next = {...(node.inputMappings || {})};
            let index = Object.keys(next).length + 1;
            let name = `input${index}`;
            while (next[name]) name = `input${++index}`;
            next[name] = {path: availablePaths(node.id)[0] || '$.input', required: true};
            node.inputMappings = next;
            markDirty();
            renderNodeInspector(node);
        });
    }

    function availablePaths(nodeId) {
        const paths = ['$', '$.input'];
        const inputProperties = state.design.definition.inputSchema?.properties || {};
        Object.keys(inputProperties).forEach(name => paths.push(`$.input.${name}`));
        const ancestors = ancestorsOf(nodeId);
        state.design.definition.nodes.filter(node => ancestors.has(node.id)).forEach(node => {
            const root = `$.nodes.${node.id}.output`;
            paths.push(root, `$.nodes.${node.id}.status`);
            let schema = node.type === 'START' ? state.design.definition.inputSchema : node.config?.outputSchema;
            Object.keys(schema?.properties || {}).forEach(name => paths.push(`${root}.${name}`));
            if (node.type === 'LLM' && node.config?.responseMode !== 'JSON_SCHEMA') paths.push(`${root}.text`);
        });
        return [...new Set(paths)];
    }

    function ancestorsOf(nodeId) {
        const result = new Set();
        const queue = [nodeId];
        while (queue.length) {
            const current = queue.shift();
            state.design.definition.edges.filter(edge => edge.to === current).forEach(edge => {
                if (!result.has(edge.from)) {
                    result.add(edge.from);
                    queue.push(edge.from);
                }
            });
        }
        return result;
    }

    function renderEdgeInspector(edge) {
        if (!edge) return renderWorkflowInspector();
        const condition = edge.condition;
        const sourcePaths = availablePaths(edge.to).filter(path => path.includes(`nodes.${edge.from}.`) || path.startsWith('$.input'));
        elements.inspectorCaption.textContent = `连线 · ${edge.from} → ${edge.to}`;
        elements.inspectorContent.innerHTML = `
            <div class="form-section"><div class="form-section-title">连线信息</div>
                ${field('来源', 'edge-from', edge.from, 'text', true)}
                ${field('目标', 'edge-to', edge.to, 'text', true)}
            </div>
            <div class="form-section"><div class="form-section-title">执行条件</div>
                <label class="switch-row"><input id="edge-condition-enabled" type="checkbox" ${condition ? 'checked' : ''}>启用条件</label>
                <div id="edge-condition-fields" ${condition ? '' : 'hidden'}>
                    <datalist id="edge-paths">${sourcePaths.map(path => `<option value="${escapeHtml(path)}"></option>`).join('')}</datalist>
                    ${field('判断路径', 'edge-condition-path', condition?.path || sourcePaths[0] || '', 'text', false, 'edge-paths')}
                    <div class="form-row"><label>运算符</label><select id="edge-condition-operator">
                        ${state.catalog.conditionOperators.map(operator => `<option ${condition?.operator === operator ? 'selected' : ''}>${operator}</option>`).join('')}
                    </select></div>
                    ${field('比较值 JSON', 'edge-condition-value', condition?.value === undefined ? '' : pretty(condition.value))}
                    <p class="muted-help">TRUTHY、FALSY、EXISTS、NOT_EXISTS 不需要比较值。</p>
                </div>
            </div>`;
        $('edge-condition-enabled').addEventListener('change', event => {
            if (event.target.checked) edge.condition = {path: sourcePaths[0] || '$.input', operator: 'TRUTHY'};
            else delete edge.condition;
            markDirty(); renderEdgeInspector(edge); renderEdges();
        });
        if (condition) {
            $('edge-condition-path').addEventListener('input', event => { edge.condition.path = event.target.value; markDirty(); });
            $('edge-condition-operator').addEventListener('change', event => { edge.condition.operator = event.target.value; markDirty(); });
            $('edge-condition-value').addEventListener('change', event => {
                const value = event.target.value.trim();
                if (!value) delete edge.condition.value;
                else edge.condition.value = parseJsonOrText(value);
                markDirty();
            });
        }
    }

    function deleteSelected() {
        if (state.selected.kind === 'node') {
            const nodeId = state.selected.id;
            state.design.definition.nodes = state.design.definition.nodes.filter(node => node.id !== nodeId);
            state.design.definition.edges = state.design.definition.edges.filter(edge => edge.from !== nodeId && edge.to !== nodeId);
            delete state.design.positions[nodeId];
        } else if (state.selected.kind === 'edge') {
            state.design.definition.edges = state.design.definition.edges.filter(edge => edgeKey(edge) !== state.selected.id);
        } else return;
        state.selected = {kind: 'workflow', id: null};
        state.validationErrors = [];
        markDirty();
        renderAll();
    }

    async function validateDefinition(showSuccess = true) {
        const result = await request('/api/dag-workflows/validate', {
            method: 'POST', body: JSON.stringify(state.design.definition)
        });
        state.validationErrors = result.errors || [];
        if (result.valid) {
            elements.validationResult.className = 'validation-result success';
            elements.validationResult.textContent = `校验通过 · ${result.topologicalOrder.join(' → ')}`;
            if (showSuccess) notify('工作流校验通过');
        } else {
            elements.validationResult.className = 'validation-result error';
            elements.validationResult.textContent = result.errors.map(issue => issue.message).join('；');
            notify(`发现 ${result.errors.length} 个配置问题`, true);
        }
        renderCanvas();
        return result.valid;
    }

    async function saveDefinition() {
        try {
            if (!await validateDefinition(false)) return;
            const code = state.design.definition.code?.trim();
            if (!code) throw new Error('请先填写工作流编码');
            state.design = await request(`/api/dag-workflows/definitions/${encodeURIComponent(code)}`, {
                method: 'PUT', body: JSON.stringify(state.design)
            });
            state.dirty = false;
            await refreshDefinitions(code);
            renderAll();
            notify('工作流草稿已保存');
        } catch (error) {
            notify(error.message, true);
        }
    }

    async function refreshDefinitions(selectedCode) {
        state.definitions = await request('/api/dag-workflows/definitions');
        elements.definitionSelect.innerHTML = '<option value="">当前未保存草稿</option>'
            + state.definitions.map(design => `<option value="${escapeHtml(design.definition.code)}">${escapeHtml(design.definition.name || design.definition.code)} · v${design.definition.version}</option>`).join('');
        if (selectedCode) elements.definitionSelect.value = selectedCode;
    }

    async function loadDefinition(code) {
        if (!code) return;
        if (state.dirty && !window.confirm('当前草稿尚未保存，确定切换吗？')) {
            elements.definitionSelect.value = state.design.definition.code || '';
            return;
        }
        try {
            clearPoll();
            state.design = await request(`/api/dag-workflows/definitions/${encodeURIComponent(code)}`);
            normalizeDesign();
            state.dirty = false;
            state.validationErrors = [];
            state.run = null;
            state.selected = {kind: 'workflow', id: null};
            renderAll();
            await refreshHistory();
        } catch (error) {
            notify(error.message, true);
        }
    }

    function normalizeDesign() {
        state.design.positions ||= {};
        state.design.definition.nodes.forEach((node, index) => {
            node.inputMappings ||= {};
            node.config ||= {};
            node.retryPolicy ||= {maxAttempts: 1, backoffMillis: 0};
            node.joinMode ||= 'ANY';
            node.timeoutSeconds ||= 120;
            state.design.positions[node.id] ||= {x: 120 + index * 260, y: 180};
        });
        state.design.definition.edges ||= [];
        state.design.definition.edges.forEach(edge => edge.id ||= uniqueEdgeId(edge.from, edge.to));
        state.design.definition.outputMappings ||= {};
    }

    async function runWorkflow() {
        try {
            if (!await validateDefinition(false)) return;
            const input = JSON.parse(elements.runInput.value || '{}');
            const started = await request('/api/dag-workflows/runs', {
                method: 'POST', body: JSON.stringify({definition: state.design.definition, input})
            });
            state.run = {...started, nodeRuns: {}, events: []};
            renderDebug();
            await pollRun(started.runId);
        } catch (error) {
            notify(`运行失败：${error.message}`, true);
        }
    }

    async function pollRun(runId) {
        clearPoll();
        const tick = async () => {
            try {
                state.run = await request(`/api/dag-workflows/runs/${encodeURIComponent(runId)}`);
                renderCanvas();
                renderDebug();
                if (TERMINAL_STATUSES.has(state.run.status)) {
                    clearPoll();
                    await refreshHistory();
                    notify(state.run.status === 'COMPLETED' ? '工作流运行完成' : `工作流运行${state.run.status}`, state.run.status !== 'COMPLETED');
                }
            } catch (error) {
                clearPoll();
                notify(error.message, true);
            }
        };
        await tick();
        if (!state.run || !TERMINAL_STATUSES.has(state.run.status)) state.pollTimer = window.setInterval(tick, 1000);
    }

    function clearPoll() {
        if (state.pollTimer) window.clearInterval(state.pollTimer);
        state.pollTimer = null;
    }

    async function refreshHistory() {
        if (!state.design) return;
        try {
            const code = state.design.definition.code || '';
            const runs = await request(`/api/dag-workflows/runs?workflowCode=${encodeURIComponent(code)}&limit=20`);
            elements.runHistory.innerHTML = '<option value="">最近运行</option>' + runs.map(run => {
                const time = run.createdAt ? new Date(run.createdAt).toLocaleString() : '';
                return `<option value="${escapeHtml(run.runId)}">${escapeHtml(run.status)} · ${escapeHtml(time)} · ${escapeHtml(run.runId.slice(0, 8))}</option>`;
            }).join('');
        } catch (error) {
            notify(error.message, true);
        }
    }

    async function loadRun(runId) {
        try {
            clearPoll();
            state.run = await request(`/api/dag-workflows/runs/${encodeURIComponent(runId)}`);
            renderCanvas();
            renderDebug();
            if (!TERMINAL_STATUSES.has(state.run.status)) await pollRun(runId);
        } catch (error) {
            notify(error.message, true);
        }
    }

    function renderDebug() {
        const run = state.run;
        const status = run?.status || 'IDLE';
        elements.runStatus.textContent = status;
        elements.runStatus.className = `run-status ${status}`;
        elements.runId.textContent = run?.runId ? `runId: ${run.runId}` : '';
        const events = run?.events || [];
        elements.runEvents.className = `run-events ${events.length ? '' : 'empty'}`;
        elements.runEvents.innerHTML = events.length ? events.map(event => `
            <div class="run-event">
                <span class="event-sequence">${event.sequence}</span>
                <span class="event-summary">${escapeHtml(event.summary)}<small>${escapeHtml(event.type)}${event.nodeId ? ` · ${escapeHtml(event.nodeId)}` : ''}</small></span>
                <span class="event-time">${event.occurredAt ? new Date(event.occurredAt).toLocaleTimeString() : ''}</span>
            </div>`).join('') : '暂无运行事件';
        renderNodeRunDetails();
    }

    function renderNodeRunDetails() {
        if (!state.run || state.selected.kind !== 'node') {
            elements.nodeRunDetails.textContent = '选择运行后的节点查看实际输入、输出和错误。';
            return;
        }
        const nodeRun = state.run.nodeRuns?.[state.selected.id];
        elements.nodeRunDetails.textContent = nodeRun ? pretty(nodeRun) : '该节点暂无运行数据。';
    }

    function fitNodes() {
        const positions = Object.values(state.design.positions || {});
        if (!positions.length) return;
        const minX = Math.min(...positions.map(item => item.x));
        const minY = Math.min(...positions.map(item => item.y));
        elements.canvasViewport.scrollTo({left: Math.max(0, minX - 80), top: Math.max(0, minY - 80), behavior: 'smooth'});
    }

    function exportDesign() {
        const blob = new Blob([JSON.stringify(state.design, null, 2)], {type: 'application/json'});
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = `${state.design.definition.code || 'workflow'}.json`;
        link.click();
        URL.revokeObjectURL(link.href);
    }

    async function importDesign(event) {
        const file = event.target.files?.[0];
        event.target.value = '';
        if (!file) return;
        try {
            const parsed = JSON.parse(await file.text());
            state.design = parsed.definition && parsed.positions !== undefined
                ? parsed
                : {status: 'DRAFT', definition: parsed.definition || parsed, positions: {}};
            normalizeDesign();
            state.selected = {kind: 'workflow', id: null};
            state.validationErrors = [];
            state.run = null;
            markDirty();
            renderAll();
            notify('工作流 JSON 已导入，请校验后保存');
        } catch (error) {
            notify(`导入失败：${error.message}`, true);
        }
    }

    function toggleDebug() {
        const shell = document.querySelector('.designer-shell');
        const collapsed = shell.classList.toggle('debug-collapsed');
        elements.debugBody.hidden = collapsed;
        elements.toggleDebugButton.textContent = collapsed ? '展开' : '收起';
    }

    function findNode(id) {
        return state.design.definition.nodes.find(node => node.id === id);
    }

    function findEdge(key) {
        return state.design.definition.edges.find(edge => edgeKey(edge) === key);
    }

    function markDirty() {
        state.dirty = true;
        state.validationErrors = [];
        elements.dirtyIndicator.textContent = '未保存';
        elements.dirtyIndicator.classList.remove('saved');
    }

    function field(label, id, value, type = 'text', readonly = false, list = null) {
        return `<div class="form-row"><label for="${id}">${escapeHtml(label)}</label><input id="${id}" type="${type}" value="${escapeHtml(value ?? '')}" ${readonly ? 'readonly' : ''} ${list ? `list="${list}"` : ''}></div>`;
    }

    function textareaField(label, id, value) {
        return `<div class="form-row"><label for="${id}">${escapeHtml(label)}</label><textarea id="${id}" spellcheck="false">${escapeHtml(value ?? '')}</textarea></div>`;
    }

    function bindJsonTextarea(id, setter) {
        $(id).addEventListener('change', event => {
            try {
                setter(JSON.parse(event.target.value || '{}'));
                event.target.value = pretty(JSON.parse(event.target.value || '{}'));
                markDirty();
            } catch (error) {
                notify(`${event.target.previousElementSibling?.textContent || 'JSON'} 格式错误：${error.message}`, true);
            }
        });
    }

    function parseJsonOrText(value) {
        if (!value) return '';
        try { return JSON.parse(value); } catch (_) { return value; }
    }

    function pretty(value) {
        return JSON.stringify(value ?? null, null, 2);
    }

    function clone(value) {
        return JSON.parse(JSON.stringify(value));
    }

    function escapeHtml(value) {
        return String(value ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#039;');
    }

    async function request(url, options = {}) {
        const response = await fetch(url, {
            headers: {'Content-Type': 'application/json', ...(options.headers || {})},
            ...options
        });
        if (!response.ok) {
            let detail;
            try { detail = await response.json(); } catch (_) { detail = {error: await response.text()}; }
            throw new Error(detail.error || detail.message || `请求失败：HTTP ${response.status}`);
        }
        if (response.status === 204) return null;
        return response.json();
    }

    let toastTimer;
    function notify(message, error = false) {
        window.clearTimeout(toastTimer);
        elements.toast.textContent = message;
        elements.toast.className = `toast visible ${error ? 'error' : ''}`;
        toastTimer = window.setTimeout(() => elements.toast.className = 'toast', 3200);
    }
})();
