import {describe, expect, it} from 'vitest';
import {routes} from '../../app.routes';
import {acquisitionChildRoutes} from './acquisition.routes';

describe('acquisition routes', () => {
  const shellRoute = routes.find(route => route.path === '' && Array.isArray(route.children));
  const children = shellRoute?.children ?? [];

  it('registers all acquisition child routes in the app shell', () => {
    for (const route of acquisitionChildRoutes) {
      const registered = children.find(child => child.path === route.path);
      expect(registered, `route '${route.path}' should be registered`).toBeDefined();
      expect(typeof registered?.loadComponent).toBe('function');
    }
  });

  it('relies on the shell AuthChildGuard instead of per-route guards', () => {
    for (const route of acquisitionChildRoutes) {
      expect(route.canActivate).toBeUndefined();
    }
  });

  it('defines the discover and wanted paths', () => {
    expect(acquisitionChildRoutes.map(route => route.path)).toEqual(['discover', 'wanted']);
  });
});
