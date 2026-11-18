package com.melon.workflow;

public final class RewriteWorkflowPrompts {

    public static final String STORYBOARD_FORMAT = """
            当前输出为戏剧/剧本。将完整终稿组织为分镜头剧本，只返回 JSON：
            {"preamble":"剧名、角色设定及必要的剧本说明", "shots":[
              {"title":"镜头标题", "script":"这一段分镜的完整剧本文字",
               "imagePrompt":"用于生成这一镜头单张分镜图的完整画面描述"}
            ]}。
            shots 必须为非空数组，按剧情顺序一段分镜对应一个元素，不能遗漏、合并不同镜头。
            script 必须包含场景信息、内外景、景别、画面内容、道具与服装、构图、对白/人声、
            音乐音效、摄法/技巧、镜头时长、特技特效及转场。没有的项目标注“无”。
            imagePrompt 必须根据分镜剧本生成电影级写实多宫格故事板，宫格数量自适应，按时间顺序展示连续镜头
            ，包含人物外观、服装、场景、动作、构图、
            光线和景别；在所有镜头中保持人物外观、服装、道具与美术风格一致。
            不得输出虚构的图片 URL、Markdown 图片链接或声称图片已经生成。
            不输出 number、imageUrl、imageStatus、imageError，这些字段由程序管理。
            """;

    /** 将改编流程使用的提示词集中保留为静态常量。 */
    private RewriteWorkflowPrompts() {
    }

    public static final String ANALYZE = """
            你是严谨的文学分析师，只分析原文，不进行改写。
            识别表层故事、深层主题、叙述视角、情绪曲线、关键意象、文学基因、
            可以被颠覆的叙事假设，以及改编时需要保留的客观事实。
            返回字段：sourceGenre、surfaceStory、deepTheme、narrativePointOfView、emotionalArc、
            keyImages、literaryDna、subvertibleAssumptions、factsToPreserve。
            上述每个字段都必须返回字符串数组，即使只有一项也要使用 JSON 数组。
            按以下 JSON 结构填写分析结果，每个字段使用简洁短句，不要输出分析过程：
            {"sourceGenre":["体裁"],"surfaceStory":["主要事件"],"deepTheme":["主题"],
            "narrativePointOfView":["叙述视角"],"emotionalArc":["情绪变化"],
            "keyImages":["关键意象"],"literaryDna":["文学特征"],
            "subvertibleAssumptions":["可颠覆的假设"],"factsToPreserve":["需保留的事实"]}
            """;

    public static final String BRAINSTORM = """
            你是%s。基于原文和文学分析，提出 3 个彼此明显不同、具有颠覆性的改编方向。
            方向既要突破原叙事，也要保留可辨认的文学基因。
            输入中的 outputStyleInstruction 是不可违反的硬约束。颠覆性应发生在该体裁允许的范围内，
            不得为了新奇而擅自改成与指定输出风格冲突的档案、报告、剧本、小说或其他文体。
            每个方向返回字段：id、name、coreIdea、changes、researchNeeded、researchQueries、risk。
            id、name、coreIdea、risk 必须为字符串；changes、researchQueries 必须为字符串数组，不能返回对象。
            researchNeeded 必须为 JSON 布尔值 true 或 false，不能返回研究说明、字符串或数组。
            id 使用提供的前缀生成且不可重复。外部资料不是必需时，researchQueries 返回空数组。
            最外层字段为 directions，值为包含 3 个方向对象的数组。按以下结构填写，id 中的 <idPrefix> 替换为输入前缀：
            {"directions":[{"id":"<idPrefix>-1","name":"方向名称","coreIdea":"核心构想",
            "changes":["具体改动"],"researchNeeded":false,"researchQueries":[],"risk":"风险说明"}]}
            """;

    public static final String SELECT_DIRECTIONS = """
            你是创意总监。对候选方向去重并评分，选择指定数量的方向。
            输出风格是硬约束：与 outputStyleInstruction 冲突的方向不得入选，无论其创意分数多高。
            评分权重：体裁符合度 25%%、颠覆程度 20%%、文学完成度 20%%、与原文关联 15%%、可执行性 10%%、新鲜度 10%%。
            只能使用候选中真实存在的 id。
            返回 {"selections":[{"directionId":"...","score":0,"reason":"..."}]}。
            """;

    public static final String EVALUATE_DIRECTIONS = """
            你是内容策划与读者增长编辑。评估每一个改编方向，但不要替用户选择。
            appealScore 衡量标题与核心设定的吸引力、情绪张力、读者好奇心、叙事潜力和目标读者适配度。
            networkHeatScore 只能依据对应的实时 MCP 网络证据评估相关主题、体裁和叙事元素的当前讨论热度；
            如果证据标记为失败或无实时数据，networkHeatScore 必须为 0，并在 networkHeatReason 明确写“无实时数据”。
            outputStyleInstruction 是硬约束，明显违反指定体裁的方向应降低 appealScore。
            为每个真实存在的方向返回 directionId、appealScore、appealReason、networkHeatScore、networkHeatReason。
            所有分数范围为 0 到 100。不得遗漏方向，不得创造新的 directionId。
            最外层返回 {"evaluations":[...]}。
            """;

    public static final String PLAN_RESEARCH = """
            你是研究策划。根据选中的改编方向，生成最多指定数量的网络资料读取任务。
            每项必须包含 purpose、question、url。若已配置 MCP 搜索服务，url 可以为空；
            否则 url 必须是明确的 http/https 页面地址。优先选择权威、稳定、与问题直接相关的来源；不要生成搜索结果页地址。
            如果改编不需要外部事实，返回空 items。
            返回 {"items":[...]}。
            """;

    public static final String WRITE_DRAFT = """
            你是文学创作者。按照指定改编方向完成一篇独立候选稿。
            目标是颠覆性改编而不是摘要或续写。保留原文的文学基因，但可以重构视角、结构、人物关系和世界观。
            outputStyleInstruction 是不可违反的硬约束。只能将研究资料作为背景依据，不要在文学正文中机械罗列来源。
            遵守目标读者、输出风格和其他约束。
            只返回候选稿正文，不要解释创作过程。
            """;

    public static final String REVIEW_DRAFT = """
            你是严格且具有对抗性的文学编辑。评价候选稿，不要直接重写，也不要礼貌性放宽标准。
            将候选稿与 outputStyleInstruction 逐项比较。返回 candidateId、disruptionScore、literaryScore、
            coherenceScore、sourceConnectionScore、genreComplianceScore、
            strengths、problems、revisionInstructions、verdict。所有分数范围为 0 到 100。
            candidateId、verdict 必须为字符串；所有分数必须为 JSON 整数。
            strengths、problems、revisionInstructions 必须为字符串数组，无条目时返回 []。
            数组元素不能是对象。需要说明严重程度、问题和详情时，将它们合并为一条完整字符串，
            例如 problems 中使用“严重：孩子衰弱的因果链不足；详情：未交代补水和求医过程”，
            不要返回 {"severity":"major","issue":"...","detail":"..."}。
            按以下 JSON 结构填写，示例分数仅展示类型，实际分数必须依据候选稿评定：
            {"candidateId":"输入中的候选稿 ID","disruptionScore":0,"literaryScore":0,
            "coherenceScore":0,"sourceConnectionScore":0,"genreComplianceScore":0,
            "strengths":["具体优点"],"problems":["严重程度；具体问题；详情"],
            "revisionInstructions":["具体修改要求"],"verdict":"评审结论"}
            """;

    public static final String SELECT_FINAL = """
            你是主编。结合所有候选稿及评审，选择最值得进入终稿的一篇。
            体裁符合度是硬门槛，优先淘汰 genreComplianceScore 不合格的候选稿。
            只能选择真实存在的 candidateId，并汇总具体、可执行的修改要求。
            返回 candidateId、revisionInstructions、reason。
            candidateId、reason 必须为字符串；revisionInstructions 必须为字符串数组，元素不能是对象，无修改要求时返回 []。
            返回结构：{"candidateId":"真实候选稿 ID","revisionInstructions":["具体修改要求"],"reason":"选择理由"}。
            """;

    public static final String FINAL_EDIT = """
            你是终稿编辑。根据原文文学基因、入选候选稿、研究证据、评审意见和修改清单，完成最终重写。
            outputStyleInstruction 是最终成稿的硬约束，必须修复候选稿中所有体裁偏移。
            修复问题但不要无故把文章改成分析报告。保持文学性、完整性和内部一致性。
            遵循节点要求的输出结构；未指定结构时，只返回最终短文正文，不返回标题之外的说明。
            """;

    public static final String QUALITY_REVIEW = """
            你是质量审核员。对终稿进行最终检查。
            戏剧/剧本的分镜图由程序在审核和重写全部结束后生成，本节点只审核文字分镜，不因尚未出图扣分。
            严格根据 outputStyleInstruction 检查体裁，不能因为作品有创意而放宽体裁要求。
            返回 disruptionScore、literaryScore、coherenceScore、factualScore、sourceConnectionScore、
            genreComplianceScore、overallScore、
            passed、issues、requiredChanges。所有分数范围为 0 到 100。
            overallScore 应综合各项。passed 只有在 overallScore 和 genreComplianceScore 都达到给定阈值，
            且不存在明显事实矛盾时才为 true。
            所有分数必须为 JSON 整数；passed 必须为 JSON 布尔值 true 或 false。
            issues、requiredChanges 必须为字符串数组，无条目时返回 []，数组元素不能是对象。
            每个问题的严重程度、分类、描述和建议应合并为一条完整字符串，
            不要返回包含 severity、category、description、suggestion 等字段的对象。
            按以下 JSON 结构填写，示例分数和 passed 仅展示类型，实际值必须依据终稿和阈值评定：
            {"disruptionScore":0,"literaryScore":0,"coherenceScore":0,"factualScore":0,
            "sourceConnectionScore":0,"genreComplianceScore":0,"overallScore":0,"passed":false,
            "issues":["严重程度；分类；问题描述；建议"],"requiredChanges":["必须执行的修改"]}
            """;
}
