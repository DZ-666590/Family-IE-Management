# 登录页：生活杂志式摄影

## 设计决策

用户选择自然光、真实居住环境与克制的人物动作，搭配安静的登录表单。照片负责生活质感，不再用问候、宣传段落、引言或五星评价填充画面。

- 桌面：左侧品牌与表单、右侧大幅摄影；窄桌面保持表单可用宽度。
- 手机（≤760px）：优先输入，隐藏装饰人像，并通过 `picture/source` 避免下载大图。
- 色彩：白色 `#FFFFFF`、墨色 `#20272E`、蓝色 `#4B6BEE`、边框灰 `#DFE4E8`；摄影保留自然肤色、浅蓝衣物与木色，不加统一蓝色滤镜。
- 字体：页面标题使用宋体系列衬线字体，表单和辅助操作沿用系统无衬线；不依赖外部字体服务。
- 登录、创建家庭、通过邀请码加入家庭共用视觉框架，保留原有身份认证与 API。
- 失败原因直接显示；请求编号收在“问题详情”中。字段标签与必要验证信息保留。

## 页面减字

删除总览问候、口号、“你的家庭财务”及各模块重复介绍。周期账单的“确认后生成收支”属于操作规则，保留；时间范围、行情过期、只读权限和错误信息不作装饰性文案删除。

投资摘要仅常驻组合市值和累计收益。计算口径通过点击或键盘展开，Escape 收起；非零缺价显示明确提示与“查看行情”入口，零缺价不再占指标卡片。

## 图片来源与使用边界

- 网站资源：`frontend/src/assets/editorial-home.jpg`，约 350 KiB，随 Vite 输出内容哈希文件名。
- 生成方式：内置 imagegen，原创 AI 生成的虚构成年人物，不代表任何实际用户、家庭或客户评价。
- 原始 PNG 留作项目本地设计输出；网站仅使用相同构图的 JPEG 编码版本。
- 不使用第三方样例的人物照片、引言或品牌标志。早先“账页成家”纸艺海报没有接入页面。

## 最终图片生成提示词

Use case: photorealistic-natural. Asset type: original portrait editorial photograph for the right half of a Chinese household-finance application's login page. A quiet, beautifully composed portrait in a real lived-in home, like the opening image of an independent lifestyle magazine. One fictional East Asian adult woman around 30, in a relaxed pale blue cotton shirt with casually rolled sleeves, seated at a walnut dining table beside a tall apartment window. Shoulder-to-waist framing, three-quarter view, head and shoulders in the upper-middle of the composition, natural posture, looking slightly left toward the window and off-camera. Thoughtful but not melancholic, lips relaxed, no promotional smile, no posed business headshot. Naturally tied dark hair with a few loose strands, realistic skin texture and subtle imperfections, minimal natural makeup, no glamorous retouching. The domestic setting is understated: a softly textured light plaster wall, sheer linen curtain, warm wood table with just an open unbranded notebook at the bottom edge; no readable writing, no laptop, no phone, no money props. Broad soft late-morning window light from the left shaping her face, gentle directional shadows, delicate interplay between cool daylight and warm room tones. A considered editorial composition rather than a stock photo: intimate but unintrusive, restrained asymmetry, tactile fabrics, quiet negative space around the subject, meaningful but not over-styled environment. Colors: cool off-white, desaturated light blue, natural warm skin, walnut brown, soft charcoal. Natural muted photographic colors, NOT a blue filter, NOT orange/teal. Analog medium-format photographic feeling, subtle fine grain, detailed eyes and real skin, soft highlight rolloff, moderate depth of field so the home context remains legible. Portrait aspect ratio 4:5, high resolution, clean full-bleed photograph. No text, no logo, no watermark, no UI, no quotation, no testimonial, no star ratings, no advertising slogan, no border. No resemblance to a known person. The portrait should carry warmth and human presence while remaining calm enough to sit beside a minimal white login form.
