import { Route, Routes } from "react-router-dom";
import { Layout } from "./components/layout/Layout";
import { AuthProvider } from "./auth/AuthContext";
import { RequireAuth } from "./auth/RequireAuth";
import ActivityPage from "./pages/ActivityPage";
import CalendarPage from "./pages/CalendarPage";
import CollectionPage from "./pages/CollectionPage";
import DiscoverListPage from "./pages/discover/DiscoverListPage";
import GenreDiscoverPage from "./pages/discover/GenreDiscoverPage";
import NetworkDiscoverPage from "./pages/discover/NetworkDiscoverPage";
import StudioDiscoverPage from "./pages/discover/StudioDiscoverPage";
import TrendingDiscoverPage from "./pages/discover/TrendingDiscoverPage";
import HomePage from "./pages/HomePage";
import InteractiveSearchPage from "./pages/InteractiveSearchPage";
import LibraryPage from "./pages/LibraryPage";
import LoginPage from "./pages/LoginPage";
import ManualGrabPage from "./pages/ManualGrabPage";
import ManualImportPage from "./pages/ManualImportPage";
import MediaDetailPage from "./pages/MediaDetailPage";
import RequestsPage from "./pages/RequestsPage";
import SearchPage from "./pages/SearchPage";
import SeasonPassPage from "./pages/SeasonPassPage";
import SetupPage from "./pages/SetupPage";
import BackupPage from "./pages/settings/BackupPage";
import DownloadClientsPage from "./pages/settings/DownloadClientsPage";
import HealthPage from "./pages/settings/HealthPage";
import ImportListsPage from "./pages/settings/ImportListsPage";
import IndexersPage from "./pages/settings/IndexersPage";
import JellyfinPage from "./pages/settings/JellyfinPage";
import JobsPage from "./pages/settings/JobsPage";
import NamingPage from "./pages/settings/NamingPage";
import NotificationsPage from "./pages/settings/NotificationsPage";
import PermissionsPage from "./pages/settings/PermissionsPage";
import PluginsPage from "./pages/settings/PluginsPage";
import QualityPage from "./pages/settings/QualityPage";
import RadarrPage from "./pages/settings/RadarrPage";
import RootFoldersPage from "./pages/settings/RootFoldersPage";
import SettingsLayout from "./pages/settings/SettingsLayout";
import SizeLimitsPage from "./pages/settings/SizeLimitsPage";
import SonarrPage from "./pages/settings/SonarrPage";
import UsersPage from "./pages/settings/UsersPage";

function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/setup" element={<SetupPage />} />

        <Route element={<RequireAuth />}>
          {/* Full-viewport overlay panels — no sidebar/topbar chrome, per the design's blurred-backdrop modal treatment. */}
          <Route path="/movies/:id/search" element={<InteractiveSearchPage />} />
          <Route path="/movies/:id/grab" element={<ManualGrabPage />} />
          <Route path="/episodes/:id/search" element={<InteractiveSearchPage />} />
          <Route path="/episodes/:id/grab" element={<ManualGrabPage />} />
          <Route path="/anime-episodes/:id/search" element={<InteractiveSearchPage />} />
          <Route path="/anime-episodes/:id/grab" element={<ManualGrabPage />} />
          {/* Search has its own full-screen shell (icon-only rail, centered command-palette input) — distinct from the standard sidebar/topbar layout. */}
          <Route path="/search" element={<SearchPage />} />

          <Route element={<Layout />}>
            <Route path="/" element={<HomePage />} />
            <Route path="/discover/genre/:mediaType/:id" element={<GenreDiscoverPage />} />
            <Route path="/discover/studio/:id" element={<StudioDiscoverPage />} />
            <Route path="/discover/network/:id" element={<NetworkDiscoverPage />} />
            <Route path="/discover/trending" element={<TrendingDiscoverPage />} />
            <Route path="/discover/list/:kind" element={<DiscoverListPage />} />
            <Route path="/library" element={<LibraryPage />} />
            <Route path="/movies/:id" element={<MediaDetailPage kind="movie" />} />
            <Route path="/collections/:tmdbId" element={<CollectionPage />} />
            <Route path="/movies/tmdb/:externalId" element={<MediaDetailPage kind="movie" />} />
            <Route path="/shows/:id" element={<MediaDetailPage kind="show" />} />
            <Route path="/shows/tmdb/:externalId" element={<MediaDetailPage kind="show" />} />
            <Route path="/anime/:id" element={<MediaDetailPage kind="anime" />} />
            <Route path="/anime/anilist/:externalId" element={<MediaDetailPage kind="anime" />} />
            <Route path="/requests" element={<RequestsPage />} />
            <Route path="/activity" element={<ActivityPage />} />
            <Route path="/calendar" element={<CalendarPage />} />
            <Route path="/season-pass" element={<SeasonPassPage />} />
            <Route path="/import" element={<ManualImportPage />} />

            <Route path="/settings" element={<SettingsLayout />}>
              <Route path="root-folders" element={<RootFoldersPage />} />
              <Route path="indexers" element={<IndexersPage />} />
              <Route path="download-clients" element={<DownloadClientsPage />} />
              <Route path="import-lists" element={<ImportListsPage />} />
              <Route path="plugins" element={<PluginsPage />} />
              <Route path="quality" element={<QualityPage />} />
              <Route path="size-limits" element={<SizeLimitsPage />} />
              <Route path="naming" element={<NamingPage />} />
              <Route path="notifications" element={<NotificationsPage />} />
              <Route path="jobs" element={<JobsPage />} />
              <Route path="health" element={<HealthPage />} />
              <Route path="backup" element={<BackupPage />} />
              <Route path="jellyfin" element={<JellyfinPage />} />
              <Route path="radarr" element={<RadarrPage />} />
              <Route path="sonarr" element={<SonarrPage />} />
              <Route path="users" element={<UsersPage />} />
              <Route path="permissions" element={<PermissionsPage />} />
            </Route>
          </Route>
        </Route>
      </Routes>
    </AuthProvider>
  );
}

export default App;
