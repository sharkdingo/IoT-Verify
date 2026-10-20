import { createApp } from 'vue'
import '@fontsource-variable/inter'
import '@fontsource-variable/space-grotesk'
import '@fontsource/material-symbols-outlined/400.css'
import '@fontsource/material-icons-round/400.css'
import 'element-plus/theme-chalk/el-icon.css'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-notification.css'
import './styles/base.css'
import './style.css'
import './styles/tailwind.css'
import './styles/board.css'
import './styles/device-motion.css'
import './styles/header-controls.css'
// After tailwind, so a utility in dialog markup can still override a shared dialog rule locally.
import './styles/dialog.css'
import App from './App.vue'
import {router} from './router';
import { i18n } from './assets/i18n.ts'
import { initializeTheme } from '@/composables/useTheme';

initializeTheme();
createApp(App).use(router).use(i18n).mount('#app');
