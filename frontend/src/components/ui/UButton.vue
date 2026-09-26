<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'primary' | 'default' | 'ghost'
    disabled?: boolean
    type?: 'button' | 'submit'
  }>(),
  { variant: 'default', type: 'button', disabled: false },
)
</script>

<template>
  <button
    :type="type"
    class="u-btn"
    :class="[
      `u-btn--${variant}`,
      { 'u-btn--disabled': disabled },
    ]"
    :disabled="disabled"
  >
    <slot />
  </button>
</template>

<style scoped>
.u-btn {
  height: 34px;
  padding: 0 14px;
  border-radius: 9px;
  border: 1px solid var(--line);
  background: #fff;
  font-size: var(--fs-13);
  color: var(--txt);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-family: var(--font-sans);
  transition: border-color 0.12s ease, background-color 0.12s ease, color 0.12s ease;
  white-space: nowrap;
}
.u-btn:hover:not(.u-btn--disabled) {
  border-color: #cdd1d8;
  background: #fafbfc;
}
.u-btn--primary {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
  font-weight: 600;
}
.u-btn--primary:hover:not(.u-btn--disabled) {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}
.u-btn--ghost {
  border-color: transparent;
  background: var(--primary-soft);
  color: var(--primary);
}
.u-btn--ghost:hover:not(.u-btn--disabled) {
  background: var(--primary-tint);
  border-color: transparent;
}
.u-btn--disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
