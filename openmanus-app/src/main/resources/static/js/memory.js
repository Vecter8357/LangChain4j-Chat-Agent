/* 会话和记忆控件只保存服务端 ID，消息与记忆正文全部持久化到 MySQL。 */
window.createMemoryUI = function (hooks) {
    const panel = document.getElementById('memory-panel');
    panel.innerHTML = `<h3>Memory(记忆)</h3>
        <label for="memory-project">项目</label><select id="memory-project"></select>
        <div class="memory-actions"><button type="button" id="memory-new-project">新建项目</button></div>
        <label for="memory-task">任务</label><select id="memory-task"></select>
        <div class="memory-actions"><button type="button" id="memory-new-task">新建任务</button><button type="button" id="memory-task-progress">更新进展</button></div>
        <label for="memory-conversation">会话</label><select id="memory-conversation"></select>
        <div class="memory-actions"><button type="button" id="memory-new-conversation">新会话</button><button type="button" id="memory-delete-conversation">删除会话</button></div>
        <small>打开会话可查看问答历史和改编结果。</small>
        <details><summary>管理长期 / 项目 / 任务记忆</summary>
            <label for="memory-scope">记录范围</label><select id="memory-scope"><option value="LONG_TERM">长期记忆</option><option value="PROJECT">项目记忆</option><option value="TASK">任务记忆</option></select>
            <label for="memory-content">明确要记住的内容</label><textarea id="memory-content" maxlength="4000" placeholder="例如：偏好文学散文，避免档案或报告格式。"></textarea>
            <div class="memory-actions"><button type="button" id="memory-save">记录</button><button type="button" id="memory-refresh">刷新</button></div>
            <div id="memory-entry-list"></div>
        </details><div id="memory-notice" class="memory-notice" role="status" aria-live="polite"></div>`;
    const $ = id => document.getElementById('memory-' + id);
    let conversations = [], tasks = [], operation = false, generation = 0;
    let conversationId = null;
    const notice = message => $('notice').textContent = message;
    async function api(path, method = 'GET', body) {
        const response = await fetch('/api/memory' + path, {method, headers: {'Content-Type': 'application/json'}, body: body === undefined ? undefined : JSON.stringify(body)});
        if (!response.ok) throw new Error(`操作失败 (${response.status})，请检查选择或刷新后重试。`);
        return response.status === 204 ? null : response.json();
    }
    function choose(select, items, placeholder) {
        select.replaceChildren(new Option(placeholder, ''));
        items.forEach(item => select.appendChild(new Option(item.name || item.title, item.id)));
    }
    function project() { return $('project').value || null; }
    function task() { return $('task').value || null; }
    function projectQuery() {
        const query = new URLSearchParams();
        if (project()) query.set('projectId', project());
        return query;
    }
    function renderConversations() {
        choose($('conversation'), conversations.map(item => {
            const title = tasks.find(candidate => candidate.id === item.taskId)?.title;
            return {...item, title: title ? `${title} · ${item.title}` : item.title};
        }), '新会话（发送时创建）');
        $('conversation').value = conversationId || '';
    }
    function resetConversation() {
        conversationId = null;
        $('conversation').value = '';
        hooks.clear();
    }
    async function refreshConversations() {
        const query = projectQuery();
        if (task()) query.set('taskId', task());
        conversations = [];
        renderConversations();
        conversations = await api('/conversations?' + query);
        renderConversations();
    }
    function scope() {
        const kind = $('scope').value;
        const id = kind === 'LONG_TERM' ? 'local' : kind === 'PROJECT' ? project() : task();
        if (!id) throw new Error('请先选择对应的项目或任务。');
        return {scope: kind, scopeId: id};
    }
    async function entries() {
        const token = ++generation;
        $('entry-list').replaceChildren();
        let selected;
        try { selected = scope(); } catch (error) { notice(error.message); return; }
        const rows = await api('/entries?' + new URLSearchParams(selected));
        if (token !== generation) return;
        rows.forEach(entry => {
            const row = document.createElement('div'); row.className = 'memory-entry';
            const text = document.createElement('p'); text.textContent = entry.content;
            const status = document.createElement('small'); status.textContent = `v${entry.revision} · ${entry.indexStatus}${entry.indexError ? ' · ' + entry.indexError : ''}`;
            const actions = document.createElement('div'); actions.className = 'memory-actions';
            const edit = document.createElement('button'); edit.type = 'button'; edit.textContent = '修改';
            edit.onclick = () => guarded(async () => {
                const content = window.prompt('修改记忆内容', entry.content);
                if (content === null || !content.trim()) return;
                await api('/entries/' + entry.id, 'PUT', {revision: entry.revision, content}); await entries();
            });
            const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = '删除';
            remove.onclick = () => guarded(async () => {
                if (!window.confirm('删除这条记忆？后续回答将不再使用它。')) return;
                await api('/entries/' + entry.id, 'DELETE'); await entries();
            });
            const rebuild = document.createElement('button'); rebuild.type = 'button'; rebuild.textContent = '重建索引';
            rebuild.onclick = () => guarded(async () => {await api('/entries/' + entry.id + '/rebuild', 'POST'); await entries();});
            actions.append(edit, remove, rebuild); row.append(text, status, actions); $('entry-list').appendChild(row);
        });
    }
    async function loadConversation() {
        conversationId = $('conversation').value || null;
        hooks.clear();
        if (!conversationId) return;
        const selected = conversations.find(c => c.id === conversationId);
        if (selected) $('task').value = selected.taskId || '';
        const basePath = '/conversations/' + encodeURIComponent(conversationId);
        const [messages, workflows] = await Promise.allSettled([
            api(basePath + '/messages'), api(basePath + '/rewrite-workflows')
        ]);
        await hooks.history(conversationId, messages.value || [], workflows.value || []);
        if (messages.status === 'rejected') throw messages.reason;
        if (workflows.status === 'rejected') throw workflows.reason;
        notice(messages.value.length === 50 ? '已加载最近 50 条消息；更早消息保存在服务器。' : '会话历史已加载。');
    }
    async function refreshProject() {
        tasks = [];
        conversations = [];
        choose($('task'), tasks, '不关联任务');
        renderConversations();
        const query = projectQuery();
        [tasks, conversations] = await Promise.all([api('/tasks?' + query), api('/conversations?' + query)]);
        choose($('task'), tasks, '不关联任务');
        renderConversations();
        await entries();
    }
    async function guarded(action) {
        if (operation || hooks.busy()) { notice('请等待当前操作或回答完成。'); return; }
        operation = true; panel.querySelectorAll('button,select').forEach(el => el.disabled = true);
        try { await action(); } catch (error) { notice(error.message); }
        finally { operation = false; panel.querySelectorAll('button,select').forEach(el => el.disabled = hooks.busy()); }
    }
    $('new-project').onclick = () => guarded(async () => {
        const name = window.prompt('项目名称'); if (!name?.trim()) return;
        const created = await api('/projects', 'POST', {name});
        choose($('project'), await api('/projects'), '不关联项目');
        $('project').value = created.id;
        resetConversation();
        await refreshProject();
    });
    $('new-task').onclick = () => guarded(async () => {
        const title = window.prompt('任务名称'); if (!title?.trim()) return;
        const created = await api('/tasks', 'POST', {projectId: project(), title});
        tasks = await api('/tasks?' + projectQuery());
        choose($('task'), tasks, '不关联任务'); $('task').value = created.id;
        resetConversation();
        await refreshConversations();
        await entries();
    });
    $('task-progress').onclick = () => guarded(async () => {
        const selected = tasks.find(item => item.id === task()); if (!selected) throw new Error('请先选择任务。');
        const progress = window.prompt('任务进展', selected.progress); if (progress === null || !progress.trim()) return;
        const status = window.prompt('任务状态：OPEN / IN_PROGRESS / COMPLETED / CANCELLED', selected.status); if (status === null) return;
        const updated = await api('/tasks/' + selected.id, 'PATCH', {status, progress});
        tasks = tasks.map(item => item.id === updated.id ? updated : item); notice('任务状态与进展已保存。');
    });
    $('new-conversation').onclick = () => guarded(async () => {resetConversation(); notice('发送消息后创建新会话。');});
    $('delete-conversation').onclick = () => guarded(async () => {
        if (!conversationId || !window.confirm('删除当前会话及其问答和改编记录？显式保存的记忆会保留。')) return;
        await api('/conversations/' + conversationId, 'DELETE');
        resetConversation();
        await refreshConversations();
        await entries();
        notice('会话已删除。');
    });
    $('project').onchange = () => guarded(async () => {resetConversation(); await refreshProject();});
    $('task').onchange = () => guarded(async () => {resetConversation(); await refreshConversations(); await entries();});
    $('conversation').onchange = () => guarded(async () => {await loadConversation(); await entries();});
    $('scope').onchange = () => guarded(entries);
    $('refresh').onclick = () => guarded(entries);
    $('save').onclick = () => guarded(async () => {
        const content = $('content').value.trim(); if (!content) throw new Error('请填写要记录的内容。');
        await api('/entries', 'POST', {...scope(), content, source: conversationId ? '用户明确记录；会话 ' + conversationId : '用户明确记录'});
        $('content').value = ''; await entries(); notice('记忆已保存，向量索引将在后台同步。');
    });
    const ready = guarded(async () => {choose($('project'), await api('/projects'), '不关联项目'); await refreshProject(); notice('会话历史自动保存；长期记忆由你明确记录。');});
    return {
        setBusy(value) { panel.querySelectorAll('button,select').forEach(el => el.disabled = value || operation); },
        async ensureConversation(title) {
            await ready;
            if (operation) throw new Error('记忆面板正在操作，请稍后重试。');
            if (conversationId) return conversationId;
            const created = await api('/conversations', 'POST', {projectId: project(), taskId: task(), title: Array.from(title).slice(0, 80).join('')});
            conversations.unshift(created);
            conversationId = created.id;
            renderConversations();
            return conversationId;
        }
    };
};
