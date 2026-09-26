<script setup lang="ts">
defineProps<{
  options: Array<{ id: string; name: string; count?: number }>
  modelValue: string
}>()
defineEmits<{ (e: 'update:modelValue', v: string): void }>()
</script>

<template>
  <div class="dept-bar">
    <button
      v-for="opt in options"
      :key="opt.id"
      class="dept-btn"
      :class="{ 'dept-btn--active': modelValue === opt.id }"
      @click="$emit('update:modelValue', opt.id)"
    >
      {{ opt.name }}
      <span v-if="opt.count != null" class="dept-btn__count">{{ opt.count }}</span>
    </button>
  </div>
</template>

<style scoped>
.dept-bar {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.dept-btn {
  padding: 6px 13px;
  border-radius: 20px;
  border: 1px solid var(--line);
  background: #fff;
  font-size: var(--fs-13);
  color: var(--txt2);
  cursor: pointer;
  font-family: inherit;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transition: border-color 0.12s ease, background-color 0.12s ease, color 0.12s ease;
}
.dept-btn:hover {
  border-color: #cdd1d8;
}
.dept-btn--active {
  background: var(--txt);
  border-color: var(--txt);
  color: #fff;
  font-weight: 600;
}
.dept-btn__count {
  font-size: var(--fs-11);
  color: var(--txt3);
}
.dept-btn--active .dept-btn__count {
  color: rgba(255,255,255,.7);
}
</style>
