import { createApp } from "vue";
import { createPinia } from "pinia";
import "katex/dist/katex.min.css";
import "./style.css";
import App from "./App.vue";
import router from "./router";
import { useAuthStore } from "./stores/authStore";
import { useUserProfileStore } from "./stores/userProfile";

async function bootstrap() {
  const app = createApp(App);
  const pinia = createPinia();

  app.use(pinia);

  const authStore = useAuthStore();
  const userProfile = useUserProfileStore();
  authStore.reloadFromStorage();
  userProfile.hydrateFromStorage();

  // Finish auth recovery before route pages mount so a late guest response cannot overwrite a login.
  await authStore.initAuth();
  await userProfile.bootstrapSession();

  app.use(router);
  app.mount("#app");
}

bootstrap();
