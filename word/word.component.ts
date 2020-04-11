import { Component } from '@angular/core'
@Component({
    templateUrl: './word.component.html',
    selector: 'app-word',
    styleUrls: [`./word.component.css`]
})
export class WordComponent {
    en: string = "Hello";
    vn: String = "xin chao";
    linkImage: String = "https://angular.io/assets/images/logos/angular/logo-nav@2x.png";
    fogot = false;

    toggleFogot() {
        this.fogot = !this.fogot;
    }

}