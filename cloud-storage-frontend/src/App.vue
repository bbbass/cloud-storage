<template>
  <el-container class="layout">
    <el-header class="header">
      <span class="brand">云盘与相册备份系统</span>
      <el-menu mode="horizontal" :default-active="$route.path" router class="menu">
        <el-menu-item index="/">系统自检</el-menu-item>
      </el-menu>
    </el-header>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<style>
body {
  margin: 0;
  background: #f5f7fa;
}
.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.brand {
  font-weight: 600;
  font-size: 16px;
  white-space: nowrap;
}
.menu {
  flex: 1;
  border-bottom: none;
}
</style>
