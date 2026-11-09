// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightThemeRapide from 'starlight-theme-rapide';

// https://astro.build/config
export default defineConfig({
	site: process.env.SITE || 'https://dohex.github.io',
	base: process.env.BASE || '/OpenRose',
	integrations: [
		starlight({
			title: 'OpenRose',
			description: 'ROSE / ROSESELSA（弱水时砂）耳机第三方控制客户端与 HyperOS 系统集成',
			plugins: [starlightThemeRapide()],
			defaultLocale: 'root',
			locales: {
				root: {
					label: '简体中文',
					lang: 'zh-CN',
				},
				en: {
					label: 'English',
					lang: 'en',
				},
			},
			social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/DOHEX/OpenRose' }],
			sidebar: [
				{
					label: '指南',
					translations: { en: 'Guides' },
					items: [
						{ label: '示例指南', translations: { en: 'Example Guide' }, slug: 'guides/example' },
					],
				},
				{
					label: '参考',
					translations: { en: 'Reference' },
					items: [{ autogenerate: { directory: 'reference' } }],
				},
			],
		}),
	],
});
