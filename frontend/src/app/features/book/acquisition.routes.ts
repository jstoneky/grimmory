import {Routes} from '@angular/router';

/**
 * Child routes for the acquisition feature (fork-only).
 * Kept in this file so app.routes.ts only needs a single spread line,
 * minimizing the merge surface against upstream.
 */
export const acquisitionChildRoutes: Routes = [
  {path: 'discover', loadComponent: () => import('./components/book-discovery/book-discovery.component').then(m => m.BookDiscoveryComponent)},
  {path: 'wanted', loadComponent: () => import('./components/wanted-books/wanted-books.component').then(m => m.WantedBooksComponent)},
];
