<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="logo">🎓</div>
        <h1>校园综合服务平台</h1>
        <p>讲座预约 · 图书馆座位 · 体育场馆 · 选课，一个账号全搞定</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="account">
          <el-input v-model="form.account" placeholder="学号 / 手机号 / 邮箱" clearable :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="handleLogin">
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>

      <el-divider><span class="divider-text">演示账号（密码均为 123456，点击填充）</span></el-divider>
      <div class="demo-accounts">
        <el-button plain size="small" @click="fill('2021001')">学号 · 林晚秋</el-button>
        <el-button plain size="small" @click="fill('13912345678')">手机号 · 陈屿</el-button>
        <el-button plain size="small" @click="fill('admin@campus.edu.cn')">邮箱 · 王教务</el-button>
      </div>
      <div class="demo-accounts" style="margin-top: 8px">
        <el-button size="small" type="success" plain @click="fill('test')">
          🧪 测试账号（每次登录自动清空预约数据）
        </el-button>
      </div>
    </div>
    <p class="footer">校园综合服务平台 · 让选课与预约更简单</p>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ account: '', password: '' })

const rules = {
  account: [{ required: true, message: '请输入学号 / 手机号 / 邮箱', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度需在 6~32 位之间', trigger: 'blur' }
  ]
}

function fill(account) {
  form.account = account
  form.password = '123456'
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    await userStore.login(form.account, form.password)
    ElMessage.success(`欢迎回来，${userStore.info.realName}`)
    router.push('/')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一弹出（40010/40011 等）
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 45%, #0ea5e9 100%);
}

.login-card {
  width: 420px;
  background: #fff;
  border-radius: 16px;
  padding: 40px 36px 28px;
  box-shadow: 0 24px 60px rgba(2, 6, 23, 0.35);
}

.brand {
  text-align: center;
  margin-bottom: 26px;
}

.logo {
  font-size: 40px;
}

.brand h1 {
  font-size: 22px;
  color: #1e293b;
  margin: 8px 0 6px;
}

.brand p {
  font-size: 12.5px;
  color: #94a3b8;
  margin: 0;
}

.submit {
  width: 100%;
  margin-top: 4px;
  letter-spacing: 6px;
}

.divider-text {
  font-size: 12px;
  color: #94a3b8;
}

.demo-accounts {
  display: flex;
  justify-content: center;
  gap: 4px;
  flex-wrap: wrap;
}

.footer {
  margin-top: 22px;
  color: rgba(255, 255, 255, 0.75);
  font-size: 12px;
}
</style>
