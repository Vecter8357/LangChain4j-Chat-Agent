/* 知识工作台：所有服务端文本使用 textContent，状态轮询不覆盖正在编辑的表单。 */
document.addEventListener('DOMContentLoaded', () => {
    const $ = id => document.getElementById(id);
    const labels = {UPLOADING:'上传中',QUEUED:'等待解析',PROCESSING:'解析 / 索引中',READY:'已就绪',FAILED:'索引失败',DELETING:'删除中',DELETE_FAILED:'删除失败',DELETED:'已删除',ACTIVE:'可用'};
    let selected = null, bases = [], documents = [], detailDoc = null, uploading = false;
    const create = (tag, text, className) => { const node=document.createElement(tag); if(text!==undefined)node.textContent=text;if(className)node.className=className;return node; };
    const notice = (message,error=false) => { $('notice').textContent=message;$('notice').className=error?'error':''; };
    async function api(path,options={}) {
        const response=await fetch('/api/knowledge'+path,options);
        if(!response.ok){let body;try{body=await response.json();}catch{}throw new Error(body?.message || body?.detail || `操作失败 (${response.status})`);}
        return response.json();
    }
    const json = body => ({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    const path = doc => `/bases/${encodeURIComponent(doc.baseId)}/documents/${encodeURIComponent(doc.id)}`;
    function action(label,fn,disabled=false) {const button=create('button',label);button.type='button';button.disabled=disabled;button.addEventListener('click',async()=>{button.disabled=true;try{await fn();}catch(e){notice(e.message,true);}finally{button.disabled=disabled;}});return button;}
    async function loadBases() {
        bases=await api('/bases');
        if(selected && !bases.some(b=>b.id===selected.id))selected=null;
        if(!selected)selected=bases[0]||null;
        else selected=bases.find(b=>b.id===selected.id);
        $('base-list').replaceChildren();
        for(const base of bases){const button=action(base.name,async()=>{selected=base;detailDoc=null;$('document-detail').hidden=true;$('search-results').replaceChildren();await refresh();});button.className='base-button'+(selected?.id===base.id?' active':'');button.append(create('small',labels[base.status]||base.status));$('base-list').append(button);}
        $('selected-name').textContent=selected?.name||'选择或创建知识库';
        $('selected-description').textContent=selected?.description||'永久文档不会在对话结束后删除。';
        $('upload').disabled=uploading||!selected||selected.status!=='ACTIVE';$('search').disabled=!selected||selected.status!=='ACTIVE';$('delete-base').disabled=!selected;
    }
    async function loadDocuments() {
        if(!selected){documents=[];return;}
        const baseId=selected.id;
        const loaded=await api(`/bases/${baseId}/documents`);
        if(selected?.id!==baseId)return;
        documents=loaded;
        $('documents').replaceChildren();
        const oldFilter=$('search-file').value;
        $('search-file').replaceChildren(new Option('知识库中的所有文件',''));
        for(const doc of documents){
            $('search-file').append(new Option(doc.name,doc.id));
            const row=create('tr'),name=create('td',doc.name);name.append(create('small',`文档 v${doc.documentVersion} · ${doc.id.substring(0,8)}`));
            const status=create('td');status.append(create('span',labels[doc.status]||doc.status,'pill state-'+doc.status.toLowerCase()));
            if(doc.error)status.append(create('small',doc.error));
            const index=create('td',doc.activeIndexId?'已有生效版本':'尚未就绪');
            if(doc.activeIndexId)index.append(create('small',doc.activeIndexId.substring(0,8)));
            if(doc.pendingIndexId)index.append(create('small',`任务 ${doc.pendingIndexId.substring(0,8)}`));
            const actions=create('td'),group=create('div',undefined,'actions');
            group.append(action('版本 / 分块',()=>showDetail(doc)),action('重建',async()=>{await api(path(doc)+'/rebuild',{method:'POST'});notice('已提交重建任务，旧索引继续可用。');await loadDocuments();},!['READY','FAILED'].includes(doc.status)));
            const upload=action('更新版本',()=>newVersion(doc),!['READY','FAILED'].includes(doc.status));
            group.append(upload,action(doc.status==='DELETE_FAILED'?'重试删除':'删除',async()=>{await api(path(doc),{method:'DELETE'});notice('已标记删除，文档已退出检索；后台正在清理所有版本。');await loadDocuments();},doc.status==='DELETING'));
            actions.append(group);row.append(name,status,index,actions);$('documents').append(row);
        }
        if(!documents.length){const row=create('tr'),cell=create('td','暂无文档。上传第一份文档，建立可引用的知识。','empty');cell.colSpan=4;row.append(cell);$('documents').append(row);}
        if(documents.some(d=>d.id===oldFilter))$('search-file').value=oldFilter;
    }
    async function refresh(){await loadBases();await loadDocuments();}
    $('base-form').addEventListener('submit',async event=>{event.preventDefault();try{selected=await api('/bases',json({name:$('base-name').value,description:$('base-description').value}));$('base-form').reset();notice('知识库已创建。');await refresh();}catch(e){notice(e.message,true);}});
    $('upload-form').addEventListener('submit',async event=>{event.preventDefault();if(!selected||uploading)return;uploading=true;const button=$('upload');button.disabled=true;try{const data=new FormData();data.append('file',$('document-file').files[0]);await api(`/bases/${selected.id}/documents`,{method:'POST',body:data});$('upload-form').reset();notice('上传完成，后台将自动解析并建立索引。');await loadDocuments();}catch(e){notice(e.message,true);}finally{uploading=false;button.disabled=selected?.status!=='ACTIVE';}});
    function newVersion(doc){const input=document.createElement('input');input.type='file';input.accept=$('document-file').accept;input.addEventListener('change',async()=>{if(!input.files.length)return;try{const body=new FormData();body.append('file',input.files[0]);await api(path(doc)+'/versions',{method:'POST',body});notice('新文档版本已提交。');await loadDocuments();}catch(e){notice(e.message,true);}});input.click();}
    $('delete-base').addEventListener('click',async()=>{if(!selected)return;try{await api(`/bases/${selected.id}`,{method:'DELETE'});notice('知识库已退出检索，后台正在删除其中所有文档。');await refresh();}catch(e){notice(e.message,true);}});
    $('refresh').addEventListener('click',()=>refresh().catch(e=>notice(e.message,true)));
    $('close-detail').addEventListener('click',()=>{$('document-detail').hidden=true;detailDoc=null;});
    async function showDetail(doc){
        const versions=await api(path(doc)+'/versions');detailDoc=doc;$('detail-title').textContent=doc.name+' · 版本与分块';$('document-detail').hidden=false;$('versions').replaceChildren();
        for(const version of versions.documents){const item=create('div',`文档 v${version.version} · ${version.name} · ${(version.size/1024).toFixed(1)} KB`,'version-item');const link=create('a',' 下载此版本');link.href='/api/knowledge'+path(doc)+`/content?version=${version.version}`;item.append(link,create('div','SHA-256 '+version.sha256,'score-line'));$('versions').append(item);}
        $('index-version').replaceChildren();
        for(const index of versions.indexes){$('index-version').append(new Option(`${index.id===doc.activeIndexId?'[当前] ':''}${index.id.substring(0,8)} · 文档v${index.documentVersion} · ${labels[index.status]||index.status} · ${index.chunkVersion} · ${index.embeddingModel}`,index.id));const item=create('div',`索引 ${index.id} · ${index.dimension}维 · ${index.collectionName}`,'version-item');item.append(create('div','Embedding版本 '+index.embeddingVersion,'score-line'));if(index.error)item.append(create('div',index.error));$('versions').append(item);}
        if(doc.activeIndexId)$('index-version').value=doc.activeIndexId;
        await loadChunks();
    }
    async function loadChunks(){if(!detailDoc)return;$('chunks').replaceChildren();const id=$('index-version').value;if(!id)return;const chunks=await api(path(detailDoc)+`/chunks?indexId=${id}`);for(const chunk of chunks){const card=create('article',undefined,'chunk-card');card.append(create('strong',`分块 ${chunk.ordinal+1} · 文档v${chunk.documentVersion}`),create('p',chunk.content));$('chunks').append(card);}if(!chunks.length)$('chunks').append(create('p','该索引暂无分块。','muted'));}
    $('index-version').addEventListener('change',()=>loadChunks().catch(e=>notice(e.message,true)));
    $('search-form').addEventListener('submit',async event=>{event.preventDefault();if(!selected)return;$('search').disabled=true;try{const result=await api('/search',json({question:$('search-question').value,knowledgeBaseIds:[selected.id],fileIds:$('search-file').value?[$('search-file').value]:[]}));$('search-results').replaceChildren();$('search-info').textContent=`${result.citations.length} 个来源 · ${result.retrievalMode} · ${result.reranker}`;for(const warning of result.warnings)$('search-results').append(create('p',warning,'muted'));for(const citation of result.citations){const card=create('article',undefined,'citation');const title=create('a',`${citation.documentName} · v${citation.documentVersion} · 分块${citation.ordinal+1}`);title.href=citation.sourceUrl;title.target='_blank';title.rel='noopener';card.append(title,create('p',citation.text),create('div',`dense ${citation.denseScore.toFixed(4)} · lexical-rank ${citation.lexicalScore.toFixed(4)} · RRF ${citation.fusionScore.toFixed(5)} · rerank ${citation.rerankScore.toFixed(4)}`,'score-line'));$('search-results').append(card);}}catch(e){notice(e.message,true);}finally{$('search').disabled=!selected;}});
    async function openCitation(){const id=new URLSearchParams(location.search).get('chunk');if(!id)return;const detail=await api('/chunks/'+encodeURIComponent(id));const doc=(await api(`/bases/${detail.chunk.baseId}/documents`)).find(d=>d.id===detail.chunk.documentId);selected=bases.find(b=>b.id===detail.chunk.baseId);await refresh();await showDetail(doc);$('index-version').value=detail.chunk.indexId;await loadChunks();$('document-detail').scrollIntoView({behavior:'smooth'});}
    refresh().then(openCitation).catch(e=>notice(e.message,true));
    setInterval(()=>{if(!document.hidden)refresh().catch(e=>notice(e.message,true));},4000);
});
