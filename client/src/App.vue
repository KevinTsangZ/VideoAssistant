<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>
    <div class="ambient-glow"></div>

    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <span class="brand-do">视频课代表</span>
          <span class="brand-video">-AI</span>
          <p class="slogan-sub">上传视频，提取文字，生成 AI 摘要</p>
        </div>

        <div class="nav-controls">
          <button class="upload-nav-btn" @click="openUploadModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
            </span>
            上传
          </button>

          <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            </span>
            登录 / 注册
          </button>

          <div v-else class="user-profile">
            <span class="user-name">:: {{ currentUser.nickname }} ::</span>
            <button class="logout-btn" @click="logout" title="退出登录">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
            </button>
          </div>

          <button class="settings-nav-btn" @click="openSettingsModal" :title="aiSettingsTitle">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"></path><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4Z"></path></svg>
            </span>
            API Key 配置
            <span class="settings-state" :class="{ configured: hasUserAiKey }">{{ hasUserAiKey ? '已配置' : 'API Key' }}</span>
          </button>

          <div v-if="uploading" class="status-pill is-active">
            <div class="status-dot"></div>
            <span class="status-text">数据传输中...</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="hero-section">


        <transition name="toast-pop">
          <div v-if="message" class="notification-bar" :class="{ 'error': message.startsWith('❌') || message.startsWith('⚠️') }">
            {{ message }}
          </div>
        </transition>
      </section>

      <section class="workspace-section">
        <div class="section-header"><h3>视频笔记</h3><div class="count-chip">{{ list.length }} TASKS</div></div>
        <div v-if="list.length === 0" class="empty-notes-state">
          <div class="empty-notes-icon" aria-hidden="true">
            <svg width="132" height="108" viewBox="0 0 132 108" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M18 30C18 25.5817 21.5817 22 26 22H51.5C54.1425 22 56.6147 23.3052 58.1043 25.4877L62 31.1964H106C110.418 31.1964 114 34.7782 114 39.1964V78C114 82.4183 110.418 86 106 86H26C21.5817 86 18 82.4183 18 78V30Z" fill="#EEF5FF"/>
              <path d="M23 39C23 34.5817 26.5817 31 31 31H101C105.418 31 109 34.5817 109 39V77C109 81.4183 105.418 85 101 85H31C26.5817 85 23 81.4183 23 77V39Z" fill="#F8FBFF" stroke="#D8E6F8" stroke-width="2"/>
              <path d="M42 56H90" stroke="#B8C7DA" stroke-width="4" stroke-linecap="round"/>
              <path d="M50 68H82" stroke="#CBD6E5" stroke-width="4" stroke-linecap="round"/>
              <circle cx="108" cy="24" r="8" fill="#EAF7F5" stroke="#BFE8E2" stroke-width="2"/>
              <circle cx="22" cy="91" r="5" fill="#EEF5FF" stroke="#D8E6F8" stroke-width="2"/>
            </svg>
          </div>
          <p class="empty-notes-text">{{ emptyNotesText }}</p>
        </div>
        <div v-else class="card-grid">
          <div v-for="item in list" :key="item.id" class="project-card">

            <button class="delete-btn" @click.stop="deleteItem(item)" title="删除此项">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
            <div class="card-meta">
              <div class="meta-icon">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
              </div>
              <div class="meta-info">
                <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                <div class="meta-tags">
                  <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                  <span class="status-indicator" :class="item.status.toLowerCase()">
                    {{ item.status === 'COMPLETED' ? 'READY' : 'PROCESSING' }}
                  </span>
                </div>
              </div>
            </div>

            <div class="action-dock">
              <button class="dock-item" @click="downloadAudio(item)">
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                </span>
                <span class="item-label">下载音频</span>
              </button>

              <button
                  class="dock-item"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="transcribe(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                </span>
                <span class="item-label">提取文字</span>
              </button>

              <button
                  class="dock-item ai-core"
                  :disabled="item.status !== 'COMPLETED'"
                  @click="aiAnalyze(item.id)"
              >
                <span class="item-icon">
                  <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                </span>
                <div class="label-group">
                  <span class="item-label ai-label-line">AI</span>
                  <span class="item-label ai-label-line">总结</span>
                </div>
                <div class="shimmer"></div>
              </button>
            </div>

            <button
                class="rerun-btn"
                :class="{ spinning: isRerunning(item.id) || isAiPending(item.aiSummary) }"
                :disabled="item.status !== 'COMPLETED' || isRerunning(item.id) || isAiPending(item.aiSummary)"
                @click.stop="rerunAnalysis(item)"
                title="重新提取文字并AI总结"
            >
              <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                   stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 12a9 9 0 1 1-2.64-6.36"></path>
                <polyline points="21 3 21 9 15 9"></polyline>
              </svg>
            </button>
          </div>
        </div>
      </section>

      <div class="sidebar-backdrop" v-if="sidebar.visible" @click="closeSidebar"></div>
      <div class="sidebar-panel" :class="{ 'is-open': sidebar.visible }">
        <div class="sidebar-header">
          <div class="sidebar-title">
            <span class="icon" v-if="sidebar.type === 'ai'">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
            </span>
            <span class="icon" v-else>
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
            </span>
            {{ sidebar.title }}
          </div>
          <button class="close-btn" @click="closeSidebar">×</button>
        </div>
        <div class="sidebar-body">
          <div v-if="sidebar.loading" class="loading-state"><div class="quantum-loader small"></div><p>数据流处理中...</p></div>
          <div v-else>
            <div v-if="sidebar.type === 'ai'" class="markdown-content" v-html="renderedMarkdown"></div>
            <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>
          </div>
        </div>
      </div>

      <div v-if="showAuthModal" class="auth-backdrop">
        <div class="auth-panel">
          <div class="auth-header">
            <h2 class="auth-title">{{ authMode === 'login' ? '用户登录' : '新用户注册' }}</h2>
            <button class="close-btn" @click="closeAuthModal">×</button>
          </div>
          <div class="auth-body">
            <div class="input-group">
              <label>USERNAME</label>
              <input v-model="authForm.username" type="text" placeholder="输入账号" />
            </div>
            <div class="input-group">
              <label>PASSWORD</label>
              <input v-model="authForm.password" type="password" placeholder="输入密码" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label>NICKNAME (昵称)</label>
              <input v-model="authForm.nickname" type="text" placeholder="设置一个好听的名字" />
            </div>
            <div class="auth-action">
              <button class="cyber-btn" @click="handleAuth" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? '立即登录' : '提交注册' }}</span>
                <span v-else>请求处理中...</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? '没有账号?' : '已有账号?' }}</span>
              <button class="toggle-link" @click="switchAuthMode">{{ authMode === 'login' ? '去注册' : '去登录' }}</button>
            </div>
            <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
          </div>
        </div>
      </div>

      <div v-if="showSettingsModal" class="settings-backdrop" @click="!settingsSaving && closeSettingsModal()">
        <div class="settings-panel" @click.stop>
          <div class="settings-header">
            <div>
              <h2 class="settings-title">API Key 配置</h2>
              <p class="settings-subtitle">
                配置自己的 OpenAI 兼容接口，用于生成视频笔记
                <span>推荐使用硅基流动</span>
              </p>
            </div>
            <button class="close-btn" @click="closeSettingsModal" :disabled="settingsSaving">×</button>
          </div>
          <div class="settings-body">
            <div class="input-group">
              <label>BASE URL</label>
              <input v-model="settingsForm.aiBaseUrl" type="text" placeholder="https://api.siliconflow.cn/v1" />
            </div>
            <div class="input-group">
              <label>API KEY</label>
              <input v-model="settingsForm.aiApiKey" type="password" :placeholder="settingsForm.maskedAiApiKey || '填写后将只保存在服务器'" />
            </div>
            <div class="input-group">
              <label>MODEL</label>
              <input v-model="settingsForm.aiModel" type="text" placeholder="deepseek-ai/DeepSeek-R1" />
            </div>
            <p class="settings-hint">不填写新的 API Key 时，会保留服务器上已保存的 Key；没有配置时会继续使用系统默认额度。</p>
            <div class="settings-actions">
              <button class="settings-default-btn" @click="restoreDefaultAiSettings" :disabled="settingsSaving">恢复默认</button>
              <button class="settings-clear-btn" @click="clearAiKey" :disabled="settingsSaving || !hasUserAiKey">清除 Key</button>
              <button class="settings-save-btn" @click="saveAiSettings" :disabled="settingsSaving">
                {{ settingsSaving ? '保存中...' : '保存设置' }}
              </button>
            </div>
            <p v-if="settingsMessage" class="settings-msg" :class="{ error: settingsError }">{{ settingsMessage }}</p>
          </div>
        </div>
      </div>

      <div v-if="showUploadModal" class="upload-backdrop" @click="!uploading && closeUploadModal()">
        <div class="upload-panel" @click.stop>
          <div class="upload-panel-header">
            <div>
              <h2 class="upload-panel-title">上传视频</h2>
              <p class="upload-panel-subtitle">选择本地文件，或粘贴一个视频链接</p>
            </div>
            <button class="close-btn" @click="closeUploadModal" :disabled="uploading">×</button>
          </div>

          <div class="upload-wrapper">
            <input
                type="file"
                id="file-input"
                @change="handleFileChange"
                accept="video/*"
                hidden
            />

            <div
                class="upload-magnet"
                :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
                @dragover.prevent="isDragOver = true"
                @dragleave.prevent="isDragOver = false"
                @drop.prevent="handleDrop"
            >
              <div class="split-container" v-if="!uploading">

                <label for="file-input" class="skew-pane pane-local">
                  <div class="pane-content unskew">
                    <div class="magnet-icon">
                      <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                    </div>
                    <span class="magnet-title">本地视频</span>
                    <span class="magnet-desc">{{ isDragOver ? '松手上传' : '点击 / 拖拽本地文件' }}</span>
                  </div>
                </label>

                <div class="split-gap"></div>

                <div class="skew-pane pane-url">
                  <div class="pane-content unskew">
                    <div class="magnet-icon">
                      <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
                    </div>
                    <span class="magnet-title">视频链接</span>
                    <span class="magnet-desc">B站 / YouTube / 抖音</span>

                    <div class="url-input-box" @click.stop>
                      <input
                          v-model="videoUrl"
                          type="text"
                          placeholder="粘贴视频链接..."
                          @keyup.enter="handleUrlUpload"
                      />
                      <button class="url-go-btn" @click="handleUrlUpload">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                      </button>
                    </div>
                  </div>
                </div>

              </div>

              <div class="magnet-content busy" v-else>
                <div class="quantum-loader"></div>
                <span class="busy-text">正在建立通道并解析资源...</span>
              </div>

              <div class="border-glow"></div>
            </div>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { marked } from 'marked'

// --- 变量定义 ---
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const uploading = ref(false)
const list = ref([])
const isDragOver = ref(false)
const sidebar = ref({ visible: false, type: 'ai', title: '', content: '', loading: false })
const currentUser = ref(null)
const showUploadModal = ref(false)
const showAuthModal = ref(false)
const showSettingsModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })
const pollingTimers = ref({})
const rerunningIds = ref({})
const settingsSaving = ref(false)
const settingsMessage = ref('')
const settingsError = ref(false)
const settingsForm = ref({
  aiBaseUrl: '',
  aiApiKey: '',
  aiModel: '',
  maskedAiApiKey: ''
})
const FREE_UPLOAD_LIMIT = 5

const freeUploadUsed = computed(() => {
  const used = Number(currentUser.value?.freeUploadUsed ?? 0)
  return Number.isFinite(used) && used > 0 ? used : 0
})

const remainingFreeUploads = computed(() => Math.max(0, FREE_UPLOAD_LIMIT - freeUploadUsed.value))

const emptyNotesText = computed(() => {
  if (!currentUser.value) {
    return '尚未登录，每个新用户可以免费上传五次视频，让AI帮您做笔记'
  }
  return `目前还没有任何笔记，您还可以免费上传${remainingFreeUploads.value}次视频，免费次数用完后需要配置自己的API Key`
})

const hasUserAiKey = computed(() => currentUser.value?.hasAiApiKey === true)

const canUploadVideo = computed(() => remainingFreeUploads.value > 0 || hasUserAiKey.value)

const aiSettingsTitle = computed(() => {
  if (!currentUser.value) return '登录后配置自己的模型 API Key'
  return hasUserAiKey.value ? '已配置自己的模型 API Key' : '配置自己的模型 API Key'
})

// Markdown 解析
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

// --- 核心业务逻辑 ---

const openUploadModal = () => {
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  if (!ensureCanUpload()) return
  showUploadModal.value = true
}

const closeUploadModal = () => {
  if (uploading.value) return
  showUploadModal.value = false
  isDragOver.value = false
}

const openSettingsModal = async () => {
  if (!currentUser.value) {
    showMsg('⚠️ 请先登录后再配置模型设置', true)
    openAuthModal()
    return
  }
  showSettingsModal.value = true
  settingsMessage.value = ''
  settingsError.value = false
  await fetchAiSettings()
}

const closeSettingsModal = () => {
  if (settingsSaving.value) return
  showSettingsModal.value = false
  settingsForm.value.aiApiKey = ''
}

const ensureCanUpload = () => {
  if (canUploadVideo.value) return true
  showMsg('⚠️ 免费上传次数已用完，请点击右上角“API Key 配置”填写自己的 API Key 后继续上传', true)
  return false
}

const handleFileChange = async (e) => {
  if (!currentUser.value) {
    e.target.value = ''
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  if (!ensureCanUpload()) {
    e.target.value = ''
    return
  }
  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleDrop = async (e) => {
  isDragOver.value = false
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  if (!ensureCanUpload()) return
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return
  const selectedFile = droppedFiles[0]
  if (!selectedFile.type.startsWith('video/')) {
    showMsg('⚠️ 仅支持上传视频文件', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

// 【普通文件上传】
const uploadFile = async () => {
  if (!file.value) return
  uploading.value = true
  message.value = '正在建立加密通道并上传数据...'
  const formData = new FormData()
  formData.append('file', file.value)
  if (currentUser.value) formData.append('userId', currentUser.value.id)

  try {
    const res = await fetch('/api/media/upload', {
      method: 'POST',
      body: formData
    })
    const text = await res.text()
    if (!res.ok) throw new Error(text || 'Upload failed')

    showMsg('✅ 本地上传完成')
    showUploadModal.value = false
    await fetchUserQuota()
    await fetchList()
    trackLatestAutoAnalysis()
  } catch (error) {
    console.error(error)
    showMsg('❌ 上传失败: ' + error.message, true)
  } finally {
    uploading.value = false
  }
}

// 【链接上传 - 修复版】
const handleUrlUpload = async () => {
  if (!videoUrl.value) return

  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  if (!ensureCanUpload()) return

  // 简单校验链接
  if (!videoUrl.value.startsWith('http')) {
    showMsg('⚠️ 请输入合法的 http/https 链接', true)
    return
  }

  uploading.value = true
  message.value = '正在解析链接并极速下载 (低码率模式)...'

  const formData = new FormData()
  formData.append('url', videoUrl.value)
  if (currentUser.value) formData.append('userId', currentUser.value.id)

  try {
    const res = await fetch('/api/media/upload-url', {
      method: 'POST',
      body: formData
    })
    // 【关键修复】现在后端会返回 500 状态码，这里能正确捕获错误了
    const text = await res.text()
    if (!res.ok) throw new Error(text)

    showMsg('✅ 链接资源已入库')
    showUploadModal.value = false
    videoUrl.value = ''
    await fetchUserQuota()
    await fetchList()
    trackLatestAutoAnalysis()
  } catch (error) {
    console.error(error)
    // 提取后端传来的具体错误信息
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = "不支持该平台链接"
    showMsg('❌ 解析失败: ' + errMsg, true)
  } finally {
    uploading.value = false
  }
}

const showMsg = (msg, isError = false) => {
  message.value = msg
  setTimeout(() => { if(message.value === msg) message.value = '' }, 4000)
}

const fetchList = async () => {
  try {
    let url = '/api/media/list'
    if (currentUser.value) {
      // 【核心修改】加一个 _t 时间戳，强制浏览器每次都发新请求，不许读缓存！
      const timestamp = new Date().getTime()
      url += `?userId=${currentUser.value.id}&_t=${timestamp}`

      const res = await fetch(url)
      const data = await res.json()
      // 倒序排列，新的在前面
      list.value = data.reverse()
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

const trackLatestAutoAnalysis = () => {
  const latest = list.value.reduce((current, item) => {
    if (!current) return item
    return Number(item.id) > Number(current.id) ? item : current
  }, null)
  if (latest && isAiPending(latest.aiSummary)) {
    startPolling(latest.id, 'ai')
    showMsg('✅ 上传完成，已自动开始生成视频笔记')
  }
}

const saveCurrentUser = (user) => {
  currentUser.value = user
  if (user) {
    localStorage.setItem('user', JSON.stringify(user))
  } else {
    localStorage.removeItem('user')
  }
}

const fetchUserQuota = async () => {
  if (!currentUser.value?.id) return
  try {
    const res = await fetch(`/api/user/quota?userId=${currentUser.value.id}&_t=${Date.now()}`)
    const data = await res.json()
    if (data.code === 200) {
      saveCurrentUser({
        ...currentUser.value,
        freeUploadUsed: data.freeUploadUsed
      })
    }
  } catch (error) {
    console.error(error)
  }
}

const fetchAiSettings = async () => {
  if (!currentUser.value?.id) return
  try {
    const res = await fetch(`/api/user/ai-config?userId=${currentUser.value.id}&_t=${Date.now()}`)
    const data = await res.json()
    if (data.code === 200) {
      settingsForm.value = {
        aiBaseUrl: data.aiBaseUrl || '',
        aiApiKey: '',
        aiModel: data.aiModel || '',
        maskedAiApiKey: data.maskedAiApiKey || ''
      }
      saveCurrentUser({
        ...currentUser.value,
        hasAiApiKey: data.hasAiApiKey === true,
        maskedAiApiKey: data.maskedAiApiKey || ''
      })
    } else {
      settingsMessage.value = data.msg || '读取模型设置失败'
      settingsError.value = true
    }
  } catch (error) {
    console.error(error)
    settingsMessage.value = '网络连接错误'
    settingsError.value = true
  }
}

const saveAiSettings = async () => {
  if (!currentUser.value?.id) return
  if (!settingsForm.value.aiBaseUrl || !settingsForm.value.aiModel) {
    settingsMessage.value = '请填写 Base URL 和模型名称'
    settingsError.value = true
    return
  }
  settingsSaving.value = true
  settingsMessage.value = ''
  settingsError.value = false
  try {
    const res = await fetch('/api/user/ai-config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: currentUser.value.id,
        aiBaseUrl: settingsForm.value.aiBaseUrl,
        aiApiKey: settingsForm.value.aiApiKey,
        aiModel: settingsForm.value.aiModel
      })
    })
    const data = await res.json()
    if (data.code === 200) {
      saveCurrentUser(data.userInfo)
      settingsForm.value.aiApiKey = ''
      settingsForm.value.maskedAiApiKey = data.maskedAiApiKey || ''
      settingsMessage.value = '模型设置已保存'
      settingsError.value = false
      showMsg('✅ 模型设置已保存')
      await fetchUserQuota()
    } else {
      settingsMessage.value = data.msg || '保存失败'
      settingsError.value = true
    }
  } catch (error) {
    console.error(error)
    settingsMessage.value = '网络连接错误'
    settingsError.value = true
  } finally {
    settingsSaving.value = false
  }
}

const clearAiKey = async () => {
  if (!currentUser.value?.id || !hasUserAiKey.value) return
  settingsSaving.value = true
  settingsMessage.value = ''
  settingsError.value = false
  try {
    const res = await fetch('/api/user/ai-config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: currentUser.value.id,
        aiBaseUrl: settingsForm.value.aiBaseUrl,
        aiModel: settingsForm.value.aiModel,
        clearApiKey: true
      })
    })
    const data = await res.json()
    if (data.code === 200) {
      saveCurrentUser(data.userInfo)
      settingsForm.value.aiApiKey = ''
      settingsForm.value.maskedAiApiKey = ''
      settingsMessage.value = 'API Key 已清除'
      settingsError.value = false
    } else {
      settingsMessage.value = data.msg || '清除失败'
      settingsError.value = true
    }
  } catch (error) {
    console.error(error)
    settingsMessage.value = '网络连接错误'
    settingsError.value = true
  } finally {
    settingsSaving.value = false
  }
}

const restoreDefaultAiSettings = async () => {
  if (!currentUser.value?.id) return
  if (!confirm('确认恢复为系统默认模型配置吗？这会清除你保存的 API Key。')) return
  settingsSaving.value = true
  settingsMessage.value = ''
  settingsError.value = false
  try {
    const res = await fetch('/api/user/ai-config', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        userId: currentUser.value.id,
        restoreDefault: true
      })
    })
    const data = await res.json()
    if (data.code === 200) {
      saveCurrentUser(data.userInfo)
      settingsForm.value = {
        aiBaseUrl: data.aiBaseUrl || '',
        aiApiKey: '',
        aiModel: data.aiModel || '',
        maskedAiApiKey: ''
      }
      settingsMessage.value = '已恢复为系统默认配置'
      settingsError.value = false
      showMsg('✅ 已恢复为系统默认配置')
      await fetchUserQuota()
    } else {
      settingsMessage.value = data.msg || '恢复默认失败'
      settingsError.value = true
    }
  } catch (error) {
    console.error(error)
    settingsMessage.value = '网络连接错误'
    settingsError.value = true
  } finally {
    settingsSaving.value = false
  }
}

const deleteItem = async (item) => {
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return
  try {
    let url = `/api/media/delete?id=${item.id}`
    if (currentUser.value) url += `&userId=${currentUser.value.id}`
    const res = await fetch(url, { method: 'DELETE' })
    const text = await res.text()
    if (text === '删除成功') {
      showMsg('文件已销毁')
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('❌ 删除请求失败', true)
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  const url = `/api/debug/download?id=${item.id}`
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('正在转码并下载...')
    const res = await fetch(url)
    if(!res.ok) throw new Error("Fail")
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ 下载完成')
  } catch (e) {
    alert("下载失败")
  }
}

const transcribe = async (id) => {
  const item = list.value.find(i => i.id === id)
  if (item && item.transcriptText) {
    openSidebar('text', '全量文字提取')
    sidebar.value.content = item.transcriptText
    sidebar.value.loading = false
    return
  }
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'text') {
    openSidebar('text', '全量文字提取')
    sidebar.value.loading = true
    sidebar.value.content = "📝 文字提取正在后台进行中..."
    return
  }
  openSidebar('text', '全量文字提取')
  sidebar.value.loading = true
  sidebar.value.content = "📝 提取任务已提交，正在识别语音流..."
  try {
    await fetch(`/api/debug/transcribe?id=${id}`)
    startPolling(id, 'text')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

// === 【核心修改】AI 分析函数，增加限流/锁错误的处理 ===
const aiAnalyze = async (id) => {
  const item = list.value.find(i => i.id === id)

  // 1. 如果已经有结果，直接显示
  if (item && isAiFinal(item.aiSummary)) {
    openSidebar('ai', 'AI总结')
    sidebar.value.content = item.aiSummary
    sidebar.value.loading = false
    return
  }

  // 2. 如果正在轮询，直接打开侧边栏
  if ((pollingTimers.value[id] && pollingTimers.value[id].type === 'ai') || (item && isAiPending(item.aiSummary))) {
    if (!pollingTimers.value[id]) startPolling(id, 'ai')
    openSidebar('ai', 'AI总结')
    sidebar.value.loading = true
    sidebar.value.content = "🚀 系统正在后台拼命计算中...\n\n(任务正在进行，无需重复提交)"
    return
  }

  // 3. 准备提交请求，打开侧边栏loading
  openSidebar('ai', 'AI总结')
  sidebar.value.loading = true
  sidebar.value.content = "🚀 正在向分布式集群请求计算资源..."

  try {
    // 请求后端
    const res = await fetch(`/api/debug/ai?id=${id}`)
    const text = await res.text()

    // 4. 【关键逻辑】检查后端返回的文本
    // 如果包含 "⚠️" (限流/锁) 或者 "❌" (报错)，说明任务被拒绝了
    if (text.includes("⚠️") || text.includes("❌")) {
      // 弹窗提示错误
      showMsg(text, true)
      // 关闭侧边栏，因为任务其实没开始
      sidebar.value.visible = false
      sidebar.value.loading = false
      return
    }

    // 5. 如果成功 (包含 "✅" 或 "🚀")，开始轮询
    startPolling(id, 'ai')
    // 在侧边栏显示后端返回的提示 (比如 "✅ 任务已投递至 RocketMQ")
    sidebar.value.content = text + "\n\n⏳ 等待消费者接单处理..."

  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

const isRerunning = (id) => rerunningIds.value[id] === true

const rerunAnalysis = async (item) => {
  if (!item || item.status !== 'COMPLETED') return
  if (isRerunning(item.id)) return
  rerunningIds.value = { ...rerunningIds.value, [item.id]: true }
  openSidebar('ai', 'AI总结')
  sidebar.value.loading = true
  sidebar.value.content = '正在重新提取文字并生成 AI总结...'
  try {
    const res = await fetch(`/api/debug/ai?id=${item.id}&force=true`)
    const text = await res.text()
    if (text.includes("⚠️") || text.includes("❌")) {
      showMsg(text, true)
      sidebar.value.loading = false
      sidebar.value.content = text
      return
    }

    const index = list.value.findIndex(i => i.id === item.id)
    if (index >= 0) {
      list.value[index] = {
        ...list.value[index],
        transcriptText: '',
        aiSummary: '[MQ] 已进入消息队列，等待调度...'
      }
    }
    startPolling(item.id, 'ai')
    showMsg('✅ 已开始重新生成视频笔记')
    sidebar.value.content = text + "\n\n⏳ 正在重新识别语音并生成 AI总结..."
  } catch (error) {
    console.error(error)
    showMsg('❌ 重新分析请求失败', true)
    sidebar.value.loading = false
    sidebar.value.content = '重新分析请求失败: ' + error
  } finally {
    rerunningIds.value = { ...rerunningIds.value, [item.id]: false }
  }
}

const isAiPending = (text = '') => {
  return text.includes('[MQ]')
      || text.includes('消息队列')
      || text.includes('等待调度')
      || text.includes('正在')
      || text.includes('任务已')
}

const isAiFinal = (text = '') => {
  if (!text) return false
  if (isAiPending(text)) return false
  return text.includes('##') || text.includes('失败') || text.includes('Error') || text.includes('超时') || text.includes('500')
}

const startPolling = (id, type) => {
  // 清理旧定时器
  if (pollingTimers.value[id]) clearInterval(pollingTimers.value[id].timer)
  console.log(`[轮询] 开始监听任务 ID: ${id}, 类型: ${type}`)

  const timer = setInterval(async () => {
    // 1. 强制刷新列表 (带时间戳防止缓存)
    await fetchList()
    const item = list.value.find(i => i.id === id)
    if (!item) return

    let isFinished = false
    let result = ''

    if (type === 'ai') {
      const text = item.aiSummary || ''

      // 【核心修改】纯文本判断逻辑，绝对不使用 Emoji
      // 条件1: 成功 (包含 Markdown 的标题特征 "##")
      const isSuccess = text.includes("##");
      // 条件2: 失败 (包含错误关键词)
      const isError = text.includes("失败") || text.includes("Error") || text.includes("超时") || text.includes("500");

      // 只要是成功或失败，都视为“结束”，停止轮询
      if (isSuccess || isError) {
        isFinished = true
        result = text
      }

    } else if (type === 'text') {
      const text = item.transcriptText || ''
      // 文字提取同理：如果有内容且长度足够，或者报错，就停止
      if (text && (text.length > 10 || text.includes("失败"))) {
        isFinished = true
        result = text
      }
    }

    // 2. 结算
    if (isFinished) {
      // 如果侧边栏正开着，更新内容
      if (sidebar.value.visible && sidebar.value.title.includes(type === 'ai' ? 'AI' : '文字')) {
        sidebar.value.content = result
        sidebar.value.loading = false
      }

      // 只有成功才提示完成，报错则提示警告
      if (result.includes("失败") || result.includes("Error")) {
        showMsg("⚠️ 任务结束，但存在错误", true)
      } else {
        showMsg("✅ 任务完成")
      }

      clearInterval(timer)
      delete pollingTimers.value[id]
    }
  }, 3000) // 3秒轮询一次

  pollingTimers.value[id] = { timer, type }

  // 5分钟强制兜底停止
  setTimeout(() => {
    if (pollingTimers.value[id]) {
      clearInterval(pollingTimers.value[id].timer)
      delete pollingTimers.value[id]
    }
  }, 300000)
}

const openSidebar = (type, title) => {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}
const closeSidebar = () => { sidebar.value.visible = false }

const openAuthModal = () => {
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}
const closeAuthModal = () => { showAuthModal.value = false }
const switchAuthMode = () => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authMessage.value = ''
}
const handleAuth = async () => {
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = '请输入完整的账号和密码'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''
  const endpoint = authMode.value === 'login' ? '/user/login' : '/user/register'
  try {
    const res = await fetch(`/api${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    const data = await res.json()
    if (data.code === 200) {
      if (authMode.value === 'login') {
        saveCurrentUser(data.userInfo)
        closeAuthModal()
        showMsg(`欢迎回来，${data.userInfo.nickname}`)
        fetchUserQuota()
        fetchAiSettings()
        fetchList()
      } else {
        authMessage.value = '注册成功，请直接登录'
        authError.value = false
        setTimeout(() => switchAuthMode(), 1000)
      }
    } else {
      authMessage.value = data.msg || '操作失败'
      authError.value = true
    }
  } catch (e) {
    console.error(e)
    authMessage.value = '网络连接错误'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}
const logout = () => {
  saveCurrentUser(null)
  list.value = []
  showMsg('已退出系统')
}
onMounted(() => {
  const savedUser = localStorage.getItem('user')
  if (savedUser) {
    try {
      currentUser.value = JSON.parse(savedUser)
    } catch(e) {}
  }
  fetchUserQuota()
  fetchAiSettings()
  fetchList()
})
</script>

<style>
/* 确保字体引用在最上方 */
@import url('https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@300;500;700&family=Syncopate:wght@700&display=swap');

:root {
  --bg-deep: #f6f8fb;
  --bg-card: #ffffff;
  --bg-soft: #eef5ff;
  --accent-lime: #2563eb;
  --accent-purple: #0f9f8f;
  --text-main: #172033;
  --text-sub: #667085;
  --text-inverse: #ffffff;
  --border-tech: #d9e2ef;
  --shadow-float: 0 18px 45px -28px rgba(31, 43, 68, 0.35);
  --shadow-glow-lime: 0 20px 45px -30px rgba(37, 99, 235, 0.55);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important; padding: 0 !important; width: 100vw !important;
  max-width: 100vw !important; min-height: 100vh !important;
  overflow-x: hidden; background-color: var(--bg-deep);
}

.app-stage { position: relative; z-index: 1; width: 100%; min-height: 100vh; color: var(--text-main); font-family: 'Inter', 'Noto Sans SC', system-ui, sans-serif; }

.ambient-noise { position: fixed; inset: 0; background: linear-gradient(135deg, rgba(37, 99, 235, 0.08), transparent 36%), linear-gradient(315deg, rgba(15, 159, 143, 0.08), transparent 30%); pointer-events: none; z-index: -1; }
.ambient-glow { position: fixed; inset: auto 0 0 auto; width: 42vw; height: 42vh; background: linear-gradient(135deg, rgba(240, 247, 255, 0), rgba(219, 234, 254, 0.8)); pointer-events: none; z-index: -2; }

/* 导航 */
.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1rem 0; background: rgba(255, 255, 255, 0.86); backdrop-filter: blur(16px); border-bottom: 1px solid var(--border-tech); }
.nav-content { max-width: 1400px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; }
.brand { display: flex; align-items: baseline; gap: 2px; }
.brand-do { font-size: 1.8rem; color: var(--accent-lime); font-weight: 800; letter-spacing: 0; }
.brand-video { font-size: 1.8rem; font-weight: 500; color: var(--text-main); }
.beta-badge { font-size: 0.7rem; font-weight: 700; background: var(--bg-soft); color: var(--accent-lime); padding: 3px 7px; border-radius: 999px; margin-left: 8px; transform: translateY(-4px); border: 1px solid #bfdbfe; }

.nav-controls { display: flex; align-items: center; gap: 15px; }
.upload-nav-btn { background: var(--accent-purple); border: 1px solid var(--accent-purple); color: #fff; padding: 8px 16px; border-radius: 8px; font-family: inherit; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.2s; font-size: 0.9rem; }
.upload-nav-btn:hover { background: #0d8b7e; border-color: #0d8b7e; box-shadow: 0 18px 36px -28px rgba(15, 159, 143, 0.8); }
.settings-nav-btn { background: var(--bg-card); border: 1px solid var(--border-tech); color: var(--text-main); padding: 8px 12px; border-radius: 8px; font-family: inherit; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.2s; font-size: 0.9rem; }
.settings-nav-btn:hover { border-color: var(--accent-lime); color: var(--accent-lime); background: var(--bg-soft); }
.settings-state { border-left: 1px solid var(--border-tech); padding-left: 8px; color: var(--text-sub); font-size: 0.74rem; font-weight: 700; }
.settings-state.configured { color: var(--accent-purple); }
.auth-btn { background: var(--accent-lime); border: 1px solid var(--accent-lime); color: #fff; padding: 8px 16px; border-radius: 8px; font-family: inherit; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.2s; font-size: 0.9rem; }
.auth-btn:hover { background: #1d4ed8; border-color: #1d4ed8; box-shadow: var(--shadow-glow-lime); }
.user-profile { display: flex; align-items: center; gap: 10px; font-size: 0.9rem; color: var(--text-main); }
.user-name { color: var(--accent-lime); }
.logout-btn { background: none; border: none; color: var(--text-sub); cursor: pointer; padding: 4px; display: flex; align-items: center; transition: color 0.3s; }
.logout-btn:hover { color: #ff4757; }

.status-pill { display: flex; align-items: center; gap: 8px; background: var(--bg-card); padding: 7px 12px; border-radius: 999px; border: 1px solid var(--border-tech); font-size: 0.8rem; color: var(--text-sub); }
.status-dot { width: 7px; height: 7px; background: var(--accent-purple); border-radius: 50%; }
.status-pill.is-active .status-dot { animation: pulse-lime 1.5s infinite alternate; }

/* Hero */
.main-container { max-width: 1200px; margin: 0 auto; padding: 3.5rem 2rem; }
.hero-section { text-align: center; margin-bottom: 3rem; animation: slideUpFade 0.8s forwards; }
.slogan-main { font-size: clamp(2.4rem, 6vw, 4.6rem); font-weight: 800; margin-bottom: 0.8rem; line-height: 1.05; letter-spacing: 0; color: #111827; }
.slogan-sub { font-size: 1.05rem; color: var(--text-sub); letter-spacing: 0; margin-bottom: 2.5rem; }

/* === [START] 核心重构：Upload Wrapper (Physical Skew) === */
.upload-wrapper { max-width: 800px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }

.upload-magnet {
  position: relative; height: 300px;
  background: var(--bg-card);
  border-radius: 12px;
  box-shadow: var(--shadow-float);
  border: 1px solid var(--border-tech);
  overflow: hidden; /* 必须隐藏溢出 */
  transition: all 0.3s;
}
.upload-magnet:hover { border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); transform: translateY(-5px); }

/* 容器布局 */
.split-container {
  display: flex; height: 100%; width: 100%;
  position: relative; overflow: hidden;
}

/* 左右面板 (物理倾斜) */
.skew-pane {
  flex: 1; height: 100%; position: relative; cursor: pointer;
  background: #ffffff;
  transition: all 0.4s ease;
  display: flex; align-items: center; justify-content: center;
  z-index: 1;
  /* 核心：直接对容器进行 skew，而不是 clip-path */
  transform: skewX(-10deg);
}

/* 增加左右面板的宽度，确保覆盖边缘 */
.pane-local { margin-left: -20px; padding-right: 20px; border-right: 1px solid var(--border-tech); }
.pane-url { margin-right: -20px; padding-left: 20px; }

/* 鼠标悬停逻辑：只改变背景色，不加外发光，防止穿模 */
.skew-pane:hover {
  background: var(--bg-soft);
  z-index: 10;
}

/* 中间缝隙 */
.split-gap { width: 4px; background: transparent; transform: skewX(-10deg); }

/* 内容回正 */
.pane-content {
  /* 必须反向 skew 回来，否则文字是斜的 */
  transform: skewX(10deg);
  display: flex; flex-direction: column; align-items: center;
  z-index: 2; transition: transform 0.3s;
}
.skew-pane:hover .pane-content { transform: skewX(10deg) scale(1.05); }

/* 互斥变暗 */
.split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: 0.55; }

.magnet-icon { color: var(--accent-lime); margin-bottom: 1rem; }
.magnet-title { font-size: 1.35rem; font-weight: 800; letter-spacing: 0; margin-bottom: 5px; }
.magnet-desc { font-size: 0.9rem; color: var(--text-sub); }

/* URL 输入框 (需回正) */
.url-input-box {
  display: flex; margin-top: 15px; border-bottom: 2px solid var(--border-tech);
  transition: all 0.3s; position: relative; z-index: 30;
}
.skew-pane:hover .url-input-box { border-color: var(--accent-lime); }
.url-input-box input {
  background: transparent; border: none; outline: none; color: var(--text-main);
  font-family: inherit; padding: 8px 5px; width: 180px; font-size: 0.9rem;
}
.url-go-btn {
  background: transparent; border: none; color: var(--accent-lime); cursor: pointer;
  padding: 0 8px; opacity: 0.7; transition: all 0.3s;
}
.url-go-btn:hover { opacity: 1; transform: translateX(3px); }

/* 处理中状态 */
.magnet-content.busy {
  height: 100%; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bg-card); position: relative; z-index: 50;
}
.busy-text { margin-top: 15px; color: var(--accent-lime); animation: pulse-lime 2s infinite; }
/* === [END] 重构结束 === */

.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 8px; box-shadow: var(--shadow-glow-lime); }
.notification-bar.error { background: #ff4757; color: #fff; }

.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }

.upload-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1800;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.28);
  backdrop-filter: blur(6px);
}

.upload-panel {
  width: min(860px, 100%);
  background: var(--bg-card);
  border: 1px solid var(--border-tech);
  border-radius: 12px;
  box-shadow: 0 24px 70px -34px rgba(15, 23, 42, 0.45);
  overflow: hidden;
  animation: slideUpFade 0.24s ease forwards;
}

.upload-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 22px 26px;
  border-bottom: 1px solid var(--border-tech);
  background: #fbfdff;
}

.upload-panel-title {
  font-size: 1.25rem;
  line-height: 1.2;
  margin-bottom: 6px;
  color: var(--text-main);
}

.upload-panel-subtitle {
  color: var(--text-sub);
  font-size: 0.92rem;
}

.upload-panel .upload-wrapper {
  max-width: none;
  margin: 0;
  opacity: 1;
  animation: none;
  perspective: 1000px;
}

.upload-panel .upload-magnet {
  height: 300px;
  border: none;
  border-radius: 0;
  box-shadow: none;
}

.upload-panel .upload-magnet:hover {
  transform: none;
}

/* Workspace */
.workspace-section { opacity: 0; animation: slideUpFade 0.8s 0.4s forwards; min-height: 420px; }
.section-header { display: flex; align-items: center; gap: 12px; margin-bottom: 2rem; border-bottom: 2px solid var(--border-tech); padding-bottom: 10px; }
.section-header h3 { font-size: 1.5rem; font-weight: 700; }
.count-chip { background: var(--bg-soft); color: var(--accent-lime); padding: 4px 10px; border-radius: 999px; font-size: 0.75rem; font-weight: 800; }
.empty-notes-state {
  min-height: 320px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 36px 20px 48px;
}
.empty-notes-icon {
  width: 132px;
  height: 108px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 18px;
}
.empty-notes-text {
  max-width: 520px;
  color: var(--text-sub);
  font-size: 0.98rem;
  line-height: 1.7;
}
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
.project-card { background: var(--bg-card); border-radius: 8px; box-shadow: var(--shadow-float); border: 1px solid var(--border-tech); overflow: hidden; transition: transform 0.2s; position: relative; }
.project-card:hover { transform: translateY(-2px); border-color: var(--accent-lime); }
.card-meta { display: flex; gap: 1.5rem; padding: 1.5rem; align-items: center; border-bottom: 1px solid var(--border-tech); background: #fbfdff; }
.meta-icon { width: 56px; height: 56px; background: var(--bg-soft); border: 1px solid #bfdbfe; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--accent-lime); }
.filename-mask { font-size: 1.1rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.meta-tags { display: flex; gap: 12px; font-size: 0.85rem; margin-top: 5px; }
.time-tag { color: var(--text-sub); }
.status-indicator { font-weight: 600; padding: 2px 8px; border-radius: 4px; }
.status-indicator.completed { color: #0f766e; border: 1px solid #99f6e4; background: #f0fdfa; }
.status-indicator.processing { color: var(--accent-purple); border: 1px solid var(--accent-purple); animation: blink 1s infinite; }

.action-dock { display: grid; grid-template-columns: 1fr 1fr 1.5fr; gap: 12px; padding: 12px 58px 12px 12px; background: #f8fafc; }
.dock-item { position: relative; border: 1px solid var(--border-tech); background: var(--bg-card); border-radius: 8px; padding: 14px; display: flex; align-items: center; justify-content: center; gap: 10px; cursor: pointer; transition: all 0.3s; color: var(--text-sub); font-family: inherit; overflow: hidden; }
.dock-item:hover:not(:disabled) { color: var(--accent-lime); border-color: var(--accent-lime); background: var(--bg-soft); }
.dock-item:disabled { opacity: 0.3; cursor: not-allowed; }
.dock-item.ai-core { border-color: var(--accent-purple); color: var(--accent-purple); }
.dock-item.ai-core .label-group { display: flex; flex-direction: column; align-items: center; z-index: 1; line-height: 1.1; }
.dock-item.ai-core .ai-label-line { display: block; }
.dock-item.ai-core .item-sub { font-size: 0.75rem; color: var(--accent-purple); opacity: 0.8; }
.dock-item.ai-core:hover:not(:disabled) { border-color: var(--accent-purple); color: var(--text-inverse); background: var(--accent-purple); }
.dock-item.ai-core:hover:not(:disabled) .item-sub { color: var(--text-inverse); }
.rerun-btn {
  position: absolute;
  right: 12px;
  bottom: 13px;
  z-index: 4;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1px solid #bfdbfe;
  background: #ffffff;
  color: var(--accent-lime);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 12px 28px -20px rgba(37, 99, 235, 0.65);
  transition: transform 0.2s ease, background 0.2s ease, color 0.2s ease, border-color 0.2s ease;
}
.rerun-btn:hover:not(:disabled) { background: var(--accent-lime); color: #ffffff; border-color: var(--accent-lime); transform: translateY(-1px); }
.rerun-btn:disabled { cursor: not-allowed; opacity: 0.5; }
.rerun-btn.spinning svg { animation: spin 0.8s linear infinite; }

/* Sidebar */
.sidebar-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(15, 23, 42, 0.22); backdrop-filter: blur(4px); z-index: 998; }
.sidebar-panel { position: fixed; top: 0; right: -600px; width: 550px; max-width: 90vw; height: 100%; background: var(--bg-card); border-left: 1px solid var(--border-tech); z-index: 999; transition: right 0.4s cubic-bezier(0.19, 1, 0.22, 1); display: flex; flex-direction: column; box-shadow: -10px 0 40px rgba(15, 23, 42, 0.16); }
.sidebar-panel.is-open { right: 0; }
.sidebar-header { padding: 20px 30px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: #fbfdff; }
.sidebar-title { font-size: 1.4rem; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 10px; }
.icon { color: var(--accent-lime); display: flex; align-items: center; }
.close-btn { background: none; border: none; color: var(--text-sub); padding: 5px; cursor: pointer; transition: color 0.3s; }
.close-btn:hover { color: var(--accent-lime); }
.sidebar-body { flex: 1; overflow-y: auto; padding: 30px; }
.loading-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--text-sub); gap: 20px; }
.markdown-content, .text-content { line-height: 1.8; color: var(--text-main); font-size: 0.95rem; }
.text-content pre { white-space: pre-wrap; font-family: "JetBrains Mono", Consolas, monospace; background: #f8fafc; padding: 15px; border-radius: 8px; border: 1px solid var(--border-tech); color: var(--text-main); }
.markdown-content h1, .markdown-content h2, .markdown-content h3 { color: var(--accent-lime); margin-top: 1.5em; margin-bottom: 0.5em; font-family: inherit; }
.markdown-content h1 { border-bottom: 1px solid var(--border-tech); padding-bottom: 10px; }
.markdown-content ul { padding-left: 20px; }
.markdown-content li { margin-bottom: 8px; color: var(--text-main); }
.markdown-content strong { color: var(--accent-lime); font-weight: 700; }
.markdown-content p { margin-bottom: 1em; }

/* 登录框 */
.auth-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(15, 23, 42, 0.24); backdrop-filter: blur(5px); z-index: 2000; display: flex; justify-content: center; align-items: center; }
.auth-panel { width: 400px; max-width: 90vw; background: var(--bg-card); border: 1px solid var(--border-tech); border-top: 3px solid var(--accent-lime); box-shadow: 0 20px 50px rgba(15, 23, 42, 0.22); display: flex; flex-direction: column; animation: slideUpFade 0.3s forwards; border-radius: 8px; overflow: hidden; }
.auth-header { padding: 20px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: #fbfdff; }
.auth-title { font-family: inherit; font-size: 1.2rem; color: var(--text-main); font-weight: 700; letter-spacing: 0; }
.auth-body { padding: 30px; }
.input-group { margin-bottom: 20px; }
.input-group label { display: block; font-family: inherit; color: var(--text-sub); font-size: 0.75rem; margin-bottom: 8px; letter-spacing: 0; font-weight: 700; }
.input-group input { width: 100%; background: #fff; border: 1px solid var(--border-tech); padding: 12px; color: var(--text-main); font-family: inherit; font-size: 1rem; outline: none; transition: all 0.3s; border-radius: 8px; }
.input-group input:focus { border-color: var(--accent-lime); box-shadow: 0 0 0 4px rgba(37, 99, 235, 0.12); }
.cyber-btn { width: 100%; background: var(--accent-lime); color: #fff; border: none; padding: 12px; font-weight: 700; font-family: inherit; cursor: pointer; transition: all 0.3s; border-radius: 8px; margin-bottom: 20px; }
.cyber-btn:hover:not(:disabled) { background: #1d4ed8; color: var(--text-inverse); box-shadow: var(--shadow-glow-lime); }
.cyber-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.auth-toggle { text-align: center; font-size: 0.85rem; font-family: inherit; color: var(--text-sub); }
.toggle-link { background: none; border: none; color: var(--accent-lime); cursor: pointer; font-weight: 700; margin-left: 5px; text-decoration: underline; }
.toggle-link:hover { color: #1d4ed8; }
.auth-msg { margin-top: 15px; text-align: center; font-family: inherit; font-size: 0.8rem; color: var(--accent-lime); }
.auth-msg.error { color: #ff4757; }

/* 模型设置 */
.settings-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.24); backdrop-filter: blur(5px); z-index: 1900; display: flex; justify-content: center; align-items: center; padding: 24px; }
.settings-panel { width: 520px; max-width: 100%; background: var(--bg-card); border: 1px solid var(--border-tech); border-radius: 8px; box-shadow: 0 24px 70px -34px rgba(15, 23, 42, 0.45); overflow: hidden; animation: slideUpFade 0.24s ease forwards; }
.settings-header { display: flex; justify-content: space-between; gap: 16px; padding: 22px 24px; border-bottom: 1px solid var(--border-tech); background: #fbfdff; }
.settings-title { font-size: 1.2rem; line-height: 1.2; margin-bottom: 6px; color: var(--text-main); }
.settings-subtitle { color: var(--text-sub); font-size: 0.9rem; line-height: 1.5; display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.settings-subtitle span { color: var(--accent-purple); background: #ecfdf5; border: 1px solid #99f6e4; border-radius: 999px; padding: 2px 8px; font-size: 0.78rem; font-weight: 700; white-space: nowrap; }
.settings-body { padding: 24px; }
.settings-hint { color: var(--text-sub); font-size: 0.84rem; line-height: 1.7; margin-top: -4px; margin-bottom: 18px; }
.settings-actions { display: flex; justify-content: flex-end; gap: 10px; }
.settings-default-btn,
.settings-clear-btn,
.settings-save-btn { border: 1px solid var(--border-tech); border-radius: 8px; padding: 10px 14px; font-family: inherit; font-weight: 700; cursor: pointer; transition: all 0.2s; }
.settings-default-btn { background: var(--bg-soft); color: var(--accent-lime); }
.settings-default-btn:hover:not(:disabled) { border-color: var(--accent-lime); background: #e0edff; }
.settings-clear-btn { background: #fff; color: var(--text-sub); }
.settings-clear-btn:hover:not(:disabled) { color: #ff4757; border-color: #fecaca; background: #fff7f7; }
.settings-save-btn { background: var(--accent-lime); border-color: var(--accent-lime); color: #fff; }
.settings-save-btn:hover:not(:disabled) { background: #1d4ed8; border-color: #1d4ed8; }
.settings-default-btn:disabled,
.settings-clear-btn:disabled,
.settings-save-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.settings-msg { margin-top: 14px; color: var(--accent-purple); font-size: 0.86rem; text-align: right; }
.settings-msg.error { color: #ff4757; }

/* 删除按钮 */
.delete-btn {
  position: absolute; top: 10px; right: 10px; background: transparent; border: none;
  color: #71757a; cursor: pointer; opacity: 0; transition: all 0.3s ease; z-index: 10; padding: 5px;
}
.project-card:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: #ff4757; transform: scale(1.2) rotate(90deg); }

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.55; } 100% { opacity: 1; } }
@keyframes blink { 50% { opacity: 0.5; } }

@media (max-width: 760px) {
  .navbar { position: relative; }
  .nav-content { padding: 0 1rem; gap: 12px; flex-wrap: wrap; }
  .brand-do,
  .brand-video { font-size: 1.45rem; }
  .nav-controls { gap: 8px; flex-wrap: wrap; justify-content: flex-start; width: 100%; }
  .upload-nav-btn,
  .settings-nav-btn,
  .auth-btn { padding: 7px 12px; font-size: 0.85rem; }
  .status-pill { padding: 6px 10px; }
  .settings-state { display: none; }

  .main-container { padding: 2.4rem 1rem; }
  .hero-section { margin-bottom: 3rem; }
  .slogan-main {
    max-width: 100%;
    font-size: clamp(1.8rem, 10vw, 2.45rem);
    line-height: 1.1;
    overflow-wrap: break-word;
  }
  .slogan-sub { font-size: 0.95rem; margin-bottom: 2rem; }

  .upload-backdrop { align-items: flex-start; padding: 16px; overflow-y: auto; }
  .settings-backdrop { align-items: flex-start; padding: 16px; overflow-y: auto; }
  .settings-panel { margin-top: 24px; }
  .settings-header,
  .settings-body { padding: 18px; }
  .settings-actions { flex-direction: column-reverse; }
  .upload-panel { width: 100%; margin-top: 24px; }
  .upload-panel-header { padding: 18px; }
  .upload-panel-title { font-size: 1.1rem; }
  .upload-panel-subtitle { font-size: 0.86rem; }
  .upload-magnet,
  .upload-panel .upload-magnet { height: auto; min-height: 420px; overflow: hidden; }
  .split-container { flex-direction: column; }
  .skew-pane {
    min-height: 210px;
    width: 100%;
    transform: none;
  }
  .pane-content,
  .skew-pane:hover .pane-content { transform: none; }
  .pane-local {
    margin-left: 0;
    padding-right: 0;
    border-right: none;
    border-bottom: 1px solid var(--border-tech);
  }
  .pane-url {
    margin-right: 0;
    padding-left: 0;
  }
  .split-gap { display: none; }
  .split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: 1; }
  .url-input-box input { width: min(210px, 60vw); }

  .card-grid { grid-template-columns: 1fr; }
  .action-dock { grid-template-columns: 1fr; }
  .sidebar-panel { width: 100%; max-width: 100vw; }
  .sidebar-header,
  .sidebar-body { padding: 18px; }
}
</style>
