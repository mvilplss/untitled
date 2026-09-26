<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { SIDE_MENU, type MenuItem } from '@/config/menu'

const route = useRoute()
const router = useRouter()

function isActive(item: MenuItem): boolean {
  if (!item.path) return false
  if (route.path === item.path) return true
  // /digital-humans/:id still counts as digital-humans being active
  if (item.path === '/digital-humans' && route.path.startsWith('/digital-humans/')) return true
  return false
}

function handleClick(item: MenuItem) {
  if (!item.path) return
  router.push(item.path)
}

const groups = computed(() => SIDE_MENU)
</script>

<template>
  <aside class="side-nav">
    <template v-for="group in groups" :key="group.key">
      <div class="side-nav__group">{{ group.label }}</div>
      <div
        v-for="item in group.items"
        :key="item.key"
        class="side-nav__item"
        :class="{
          'side-nav__item--active': isActive(item),
          'side-nav__item--disabled': !item.path,
        }"
        :title="!item.path ? item.disabledTip : undefined"
        @click="handleClick(item)"
      >
        <span class="side-nav__ico">{{ item.icon }}</span>
        <span class="side-nav__label">{{ item.label }}</span>
        <span v-if="item.badge" class="side-nav__badge">{{ item.badge }}</span>
      </div>
    </template>
  </aside>
</template>

<style scoped>
.side-nav {
  width: 212px;
  flex: 0 0 212px;
  background: #fff;
  border-right: 1px solid var(--line);
  padding: 12px 10px;
  overflow-y: auto;
}
.side-nav__group {
  font-size: 11.5px;
  color: var(--txt3);
  padding: 12px 10px 6px;
  letter-spacing: 0.5px;
  font-weight: 600;
}
.side-nav__item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: 9px;
  color: var(--txt2);
  cursor: pointer;
  font-size: 13.5px;
  user-select: none;
  transition: background-color 0.12s ease, color 0.12s ease;
}
.side-nav__item:hover:not(.side-nav__item--disabled) {
  background: #f6f7f9;
  color: var(--txt);
}
.side-nav__item--active {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 600;
}
.side-nav__item--disabled {
  opacity: 0.42;
  cursor: not-allowed;
}
.side-nav__ico {
  width: 16px;
  text-align: center;
  font-size: 14px;
}
.side-nav__badge {
  margin-left: auto;
  background: var(--line-soft);
  color: var(--txt2);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 10px;
}
.side-nav__item--active .side-nav__badge {
  background: #dbe2ff;
  color: var(--primary);
}
</style>
