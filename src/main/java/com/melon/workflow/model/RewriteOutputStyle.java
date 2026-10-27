package com.melon.workflow.model;

public enum RewriteOutputStyle {
    LITERARY_PROSE("文学散文", "使用连续、完整的文学散文段落；不得写成档案、报告、公文、新闻、剧本或 Markdown 章节清单。"),
    PRESERVE_ORIGINAL_GENRE("保持原文体裁", "保持分析节点识别出的原文体裁和基本文体结构；创新只能发生在视角、人物关系、情节或结局上。"),
    EXPERIMENTAL("文体实验", "允许明显的结构和文体实验，但必须保持可读性、文学完整性以及与原文文学基因的联系。"),
    FICTION("小说化改编", "改写成具有场景、人物行动、对话、冲突和完整叙事弧线的小说，不得写成分析报告或条目汇编。"),
    DRAMA_SCRIPT("戏剧/剧本", "使用规范的戏剧或影视剧本结构，包含场景、角色、动作和对白，并形成完整戏剧冲突。" +
            "要包含分镜头剧本具体描述(场景信息、内外景、景别、画面内容、道具与服装、构图、对话/人声、音乐音效、摄法/技巧 、镜头时长、特技特效、转场等)"),
    DOSSIER_EPISTOLARY("档案或书信体", "使用档案、报告、书信、日记或材料汇编式叙事，通过文献之间的关系推进故事。");

    private final String displayName;
    private final String instruction;

    RewriteOutputStyle(String displayName, String instruction) {
        this.displayName = displayName;
        this.instruction = instruction;
    }

    public String displayName() {
        return displayName;
    }

    public String instruction() {
        return instruction;
    }
}
