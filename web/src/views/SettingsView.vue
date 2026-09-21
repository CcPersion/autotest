<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '../components/AppIcon.vue'

const tab = ref('模型服务')
const tabs = ['模型服务', 'Runner', 'JMeter', '通知', '安全与审计']
</script>

<template>
  <section class="settings-layout">
    <aside class="settings-nav"><span class="section-overline">CONFIGURATION</span><h3>系统设置</h3><button v-for="item in tabs" :key="item" :class="{ active: tab === item }" @click="tab = item"><AppIcon :name="item === '模型服务' ? 'spark' : item === 'Runner' ? 'runs' : item === 'JMeter' ? 'terminal' : item === '通知' ? 'bell' : 'lock'" :size="16" />{{ item }}<AppIcon name="chevron" :size="13" /></button></aside>
    <div class="settings-content panel-surface">
      <header class="settings-head"><div><span class="section-overline">{{ tab === '模型服务' ? 'MODEL PROVIDER' : 'SYSTEM SERVICE' }}</span><h2>{{ tab }}</h2><p>{{ tab === '模型服务' ? '配置兼容式模型接入。模型只能通过结构化领域工具操作测试资产。' : '查看和管理平台基础服务状态。' }}</p></div><button class="primary-button">保存配置</button></header>
      <template v-if="tab === '模型服务'">
        <div class="settings-section"><div class="settings-label"><h3>默认模型</h3><p>用于用例生成、资产修改和报告解释。</p></div><div class="settings-form"><label>接口类型<div class="select-like"><span>OpenAI Compatible API</span><AppIcon name="chevron" :size="13" /></div></label><label>服务地址<input value="https://api.example.com/v1" /></label><div class="settings-two"><label>模型名称<input value="enterprise-reasoning" /></label><label>API Key<div class="secret-input"><input value="••••••••••••••••" readonly /><AppIcon name="lock" :size="15" /></div></label></div><div class="inline-health"><span><i></i>连接正常</span><small>上次检查：今天 22:41 · 638 ms</small><button class="secondary-button">测试连接</button></div></div></div>
        <div class="settings-section"><div class="settings-label"><h3>模型权限</h3><p>这些限制由平台强制执行，模型不能自行修改。</p></div><div class="permission-list"><div><span><AppIcon name="check" /></span><p><strong>读取当前项目结构化资产</strong><small>请求和响应样本会先脱敏、截断。</small></p></div><div><span><AppIcon name="check" /></span><p><strong>生成待确认的资产修改</strong><small>确认前不会写入数据库。</small></p></div><div class="blocked"><span><AppIcon name="close" /></span><p><strong>禁止直接运行和危险操作</strong><small>不能执行脚本、写 SQL、通知或调用 Runner。</small></p></div></div></div>
      </template>
      <template v-else>
        <div class="service-overview"><div class="service-status-icon"><AppIcon :name="tab === '安全与审计' ? 'lock' : 'check'" :size="24" /></div><div><span class="enabled-pill">运行正常</span><h3>{{ tab }} 服务</h3><p>{{ tab === 'Runner' ? '单 Runner 模式 · 最近心跳 4 秒前 · 当前执行 1 个任务' : tab === 'JMeter' ? 'Apache JMeter 5.6.3 · CLI 模式 · 组件白名单已启用' : tab === '通知' ? '通用 Webhook 已配置 · 最近发送成功' : '审计记录和统一脱敏规则已启用' }}</p></div><button class="secondary-button">查看详情</button></div>
        <div class="settings-section full"><div class="settings-label"><h3>当前配置</h3><p>原型仅展示信息结构，不会修改真实服务。</p></div><div class="config-facts"><div><span>运行模式</span><strong>{{ tab === 'Runner' ? '单任务串行' : '受控模式' }}</strong></div><div><span>健康检查</span><strong class="success-text">通过</strong></div><div><span>最近更新</span><strong>今天 21:18</strong></div></div></div>
      </template>
    </div>
  </section>
</template>
