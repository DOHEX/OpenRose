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
			components: { Hero: './src/components/Hero.astro' },
			customCss: ['./src/styles/theme.css', './src/styles/home.css'],
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
			editLink: { baseUrl: 'https://github.com/DOHEX/OpenRose/edit/main/website/' },
			sidebar: [
				{
					label: '指南',
					translations: { en: 'Guides' },
					items: [
						'guides/getting-started',
						'guides/background-control',
						'guides/hyperos-integration',
						'guides/linux-desktop',
						'guides/troubleshooting',
						'guides/feedback',
					],
				},
				{
					label: '参考',
					translations: { en: 'Reference' },
					items: [
						'reference/supported-earphones',
						'reference/features',
						'reference/platforms',
						'reference/permissions',
					],
				},
				{
					label: '开发',
					translations: { en: 'Development' },
					items: ['development/contributing'],
				},
			],
		}),
	],
});
